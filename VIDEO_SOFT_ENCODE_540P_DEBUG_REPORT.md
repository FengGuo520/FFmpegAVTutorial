# VideoSoftEncode 540P 调试报告

## 1. 背景

当前工程新增了 `VideoSoftEncodeActivity`，用于演示：

- `Camera2` 采集视频
- `ImageReader(YUV_420_888)` 获取原始帧
- Kotlin 层转换为 `I420`
- JNI 调用 FFmpeg `libx264`
- 输出 `.h264` 裸流

在测试 `540p` 软编码时，最初出现了 `writeSoftVideoFrame failed: -22` 的报错。

`-22` 对应的是：

- `EINVAL`
- 即“传入参数不合法”

本次调试的目标就是定位：

1. 为什么 `540p` 软编码会报 `-22`
2. 为什么硬编码页面选 `540p` 却没有暴露同样问题
3. 最终应该如何设计 `540p` 软编码链路

---

## 2. 初始现象

最早出现的日志大意如下：

```text
writeSoftVideoFrame failed result=-22 width=960 height=720
target=960x540
reader=960x540
```

这说明一开始我们遇到的是：

- 编码器按 `960x540` 打开
- 但实际送进 JNI 的帧是 `960x720`

因此 native 层尺寸校验失败，返回 `EINVAL(-22)`。

当时的直观判断是：

- Camera2 分辨率切换后，旧会话帧可能还混进来了

于是先做了“会话重建完成后再启动编码”的保护。

---

## 3. 第二轮日志后的新结论

第二轮补日志后，得到了一组更关键的信息：

```text
startEncodingInternal target=960x540 reader=960x540
...
writeSoftVideoFrame failed result=-22 width=960 height=720 target=960x540 reader=960x540
```

这说明问题并不只是“旧会话尾帧混入”，而是：

- `ImageReader` 表面参数看起来是 `960x540`
- 但 `onImageAvailable()` 实际拿到的 `Image` 却是 `960x720`

进一步说明：

- 在当前设备上，`YUV_420_888` 这条采集路径，对 `540p` 并不稳定
- 或者说，设备并没有真正按我们期望的方式原生给出 `960x540`

于是需要重新思考：

- 不能再假设“采集尺寸 = 编码尺寸”

---

## 4. 第三轮日志后的最终定位

引入“采集尺寸”和“编码尺寸”分离后，日志变成了：

```text
openCamera previewSize=1232x1008 captureSize=1280x720 target=960x540
prepareImageReader width=1280 height=720
createCameraSession preview=1232x1008 capture=1280x720 captureTarget=1280x720 encodeTarget=960x540
startEncodingInternal encodeTarget=960x540 capture=1280x720 reader=1280x720
soft encode progress captured=10 encoded=10 resolution=1280x720
soft encode progress captured=20 encoded=20 resolution=1280x720
...
```

这一组日志非常关键，它给出最终结论：

### 当前设备上，软编码 540p 实际跑的是：

- 相机原始采集：`1280x720`
- Kotlin 层拿到原始帧：`1280x720`
- 编码目标：`960x540`
- 编码前先做缩放：`1280x720 -> 960x540`
- 再送入 `libx264`

也就是说：

> `540p` 软编码最终并不是“相机直接输出 540p 原始帧”，而是“先采 720p，再缩到 540p 进行编码”。

---

## 5. 为什么会报 -22

native 层 `WriteSoftVideoFrame(...)` 对输入有严格校验：

- 传入的宽高必须和打开编码器时声明的宽高一致
- 数据长度也必须匹配该分辨率对应的 `I420` 大小

如果编码器按 `960x540` 打开，那么它期望的是：

- `width = 960`
- `height = 540`
- `I420` 数据大小 = `960 * 540 * 3 / 2`

一旦送进去的是：

- `960x720`
- 或 `1280x720`

就会触发参数校验失败，返回：

```text
AVERROR(EINVAL) = -22
```

所以 `-22` 的本质不是“FFmpeg 编码器坏了”，而是：

> 上层送给 `libx264` 的原始帧尺寸和编码器打开时声明的目标尺寸不一致。

---

## 6. 为什么硬编码页面选 540P 没有暴露这个问题

这是本次调试里非常重要的一个认识。

### 硬编码链路

硬编码页面走的是：

```text
Camera2 -> Surface -> MediaCodec.createInputSurface() -> H.264
```

特点是：

- Camera2 并不把原始 YUV 字节数组直接交给 Java/Kotlin
- 图像通过 `Surface / GraphicBuffer` 直接送入硬编码器
- 编码器本身是按目标分辨率配置好的，例如 `960x540`

因此在硬编码页面里：

- 我们能确认的是“最终编码输出尺寸是 960x540”
- 但我们并没有直接拿到原始 `Image(width, height)`
- 所以也看不到“相机底层原始输出到底是不是 960x540”

也就是说：

> 硬编码页的 `540p`，本质上是“编码器输出目标 540p”；  
> 软编码页的 `540p`，则要求“送给编码器的原始 YUV 帧本身就是 540p”。

这两件事不是一个层面的 540p。

---

## 7. 为什么软编码更容易暴露设备尺寸差异

软编码链路是：

```text
Camera2 -> ImageReader(YUV_420_888) -> Image(width,height) -> I420 -> libx264
```

在这条链路里：

- 我们真的拿到了设备送出的原始帧
- 每一帧都有明确的：
  - `image.width`
  - `image.height`
- `libx264` 又要求输入帧尺寸必须严格匹配

所以：

- 如果设备不给你原生 `960x540`
- 你就必须自己做缩放

这也是为什么：

- 硬编码页面看起来“540p 一切正常”
- 但软编码页面会明确暴露“设备实际只给了 720p 采集帧”

---

## 8. 本次最终方案

为了解决这个问题，`VideoSoftEncodeActivity` 现在采用了“两层尺寸模型”。

### 8.1 尺寸划分

#### 1. `previewSize`

用于：

- `TextureView` 本地预览

特点：

- 服务于界面显示
- 不直接决定编码输出尺寸

#### 2. `captureSize`

用于：

- `ImageReader(YUV_420_888)`

特点：

- 表示 Camera2 真正往我们代码里送的原始帧尺寸
- 当前这台设备在 `540p` 目标下，选到的是 `1280x720`

#### 3. `encodeTarget`

用于：

- `libx264` 最终编码尺寸

特点：

- 由页面选项决定
- 本次 `540p` 对应 `960x540`

---

### 8.2 最终数据流

当前 `540p` 软编码的实际数据流如下：

```text
Camera2
  ->
ImageReader(YUV_420_888, 1280x720)
  ->
Image(width=1280, height=720)
  ->
Kotlin 拷贝 plane，拼成 I420(1280x720)
  ->
Kotlin 软件缩放
  ->
I420(960x540)
  ->
JNI
  ->
FFmpeg libx264
  ->
H.264 裸流(960x540)
```

也就是说：

> 当前工程中 `540p` 软编码的本质是：  
> **720p 采集 -> 540p 缩放 -> 540p 编码**

---

## 9. 停止时仍出现的一条 -22 日志如何理解

在编码停止后，还出现过这样一条日志：

```text
closeSoftVideoEncoder result=FFmpeg libx264 soft video encode completed.
...
writeSoftVideoFrame failed result=-22 width=1280 height=720 target=960x540 capture=1280x720 reader=1280x720
```

这条日志和主流程失败不是一回事。

它更像是：

- 用户已经点击停止
- native 编码器已经关闭
- 但 Camera2 / ImageReader 队列里还有最后一帧到达
- 回调仍然尝试写入
- 此时编码器会话已经结束，于是返回 `-22`

因此它的性质是：

- **停止瞬间的尾帧竞态**
- 不是主链路编码失败

从主日志上看，真正编码阶段已经连续完成了大量帧：

```text
captured=10 encoded=10
captured=20 encoded=20
...
captured=330 encoded=330
```

所以可以确认：

- `540p` 主流程已经跑通

---

## 10. 本次调试的核心结论

### 结论 1

当前设备在 `YUV_420_888` 采集路径下，并不会稳定提供原生 `960x540` 帧。

### 结论 2

软编码链路不能简单假设：

```text
采集尺寸 = 编码尺寸
```

### 结论 3

硬编码页面之所以没有暴露该问题，是因为硬编码走的是：

```text
Camera -> Surface -> MediaCodec
```

它把底层图像缓冲适配细节隐藏了，上层并没有直接拿到原始帧尺寸。

### 结论 4

当前工程中 `540p` 软编码应采用：

```text
先选设备稳定支持的采集尺寸
-> 再在应用层缩放到目标编码尺寸
-> 再送给 libx264
```

也就是：

```text
720p采集 -> 540p缩放 -> 540p编码
```

---

## 11. 对当前工程的建议

### 11.1 当前方案建议保留

当前 `VideoSoftEncodeActivity` 的设计已经是合理方案：

- `captureSize` 和 `encodeTarget` 分离
- 优先拿设备支持的稳定 YUV 尺寸
- 编码前软件缩放

这比强行要求设备原生输出 `540p` 更稳。

### 11.2 后续可以继续优化

后续可继续补两项增强：

1. 页面上直接展示：
   - `Capture Size`
   - `Encode Target`
   - 让实验过程更直观

2. 在停止编码时更早断开 `ImageReader` 回调
   - 消除停止瞬间尾帧写入导致的收尾 `-22` 日志

---

## 12. 一句话总结

这次 `540p` 软编码报错的根因不是 FFmpeg 本身，而是：

> 当前设备在 `ImageReader(YUV_420_888)` 路径下并不稳定提供原生 `960x540` 帧，  
> 因此不能直接把采集帧当成 `540p` 送给 `libx264`，  
> 正确做法是先按设备稳定支持的 `1280x720` 采集，再缩放到 `960x540` 后编码。
