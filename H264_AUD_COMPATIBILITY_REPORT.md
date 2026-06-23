# H.264 裸流中 NAL / Access Unit / AUD / SPS / PPS / IDR 与 VLC 兼容性说明

## 1. 背景

当前工程在 `VideoSoftEncodeActivity` 中引入了 FFmpeg `libx264` 软编码，输出 `.h264` 裸流文件。

测试现象如下：

- 软编码输出的 `.h264`：
  - `ffplay` 可以正常播放
  - `VLC` 最初无法正常播放
- 硬编码输出的 `.h264`：
  - `ffplay` 可以播放
  - `VLC` 也可以播放

进一步对比后确认：

- 软编码版本开启了 `aud=1`
- 去掉 `aud=1` 后，`VLC` 可以正常播放软编码输出的 `.h264`

这说明本次问题并不是“软编码码流损坏”，而是“`VLC` 对带 `AUD` 的裸 H.264 兼容性更挑”。

---

## 2. 先理解几个核心概念

### 2.1 NAL Unit 是什么

`NAL Unit` 全称是 `Network Abstraction Layer Unit`，中文常叫：

- NAL 单元
- H.264 码流基本单元

H.264 裸流本质上就是由一个个 NAL 单元顺序拼接起来的。

一个 `.h264` 文件里常见的 NAL 类型包括：

- `type 7`：`SPS`
- `type 8`：`PPS`
- `type 5`：`IDR slice`
- `type 1`：普通非 IDR 图像 slice
- `type 9`：`AUD`
- `type 6`：`SEI`

在 Annex B 裸流里，NAL 单元前面通常带起始码：

```text
00 00 01
```

或

```text
00 00 00 01
```

所以我们常说：

- `.h264` 裸流 = 一串带起始码的 NAL 单元

---

### 2.2 Access Unit 是什么

`Access Unit` 可以理解成：

- 一次可解码显示的图像访问单元
- 通常近似对应“一帧图像”

更直白一点：

- `NAL Unit` 是码流里的“小块”
- `Access Unit` 是由若干相关 NAL 组成的一整帧

例如一帧 IDR 图像，可能在逻辑上属于一个 Access Unit，里面可能包含：

- `AUD`
- `SPS`
- `PPS`
- `SEI`
- `IDR slice`

并不是每帧都一定要有这么多内容，但可以这样理解它们的层级关系：

```text
Access Unit（近似一帧）
  ├─ AUD（可选）
  ├─ SPS（有时重复发）
  ├─ PPS（有时重复发）
  ├─ SEI（可选）
  └─ slice / IDR slice（真正图像数据）
```

---

### 2.3 AUD 是什么

`AUD` 全称是 `Access Unit Delimiter`，中文常叫：

- 访问单元分隔符
- 帧边界提示头

它的作用不是存图像内容，而是告诉解码器或分析器：

- 这里开始了一个新的 Access Unit
- 也可以粗略理解成“新的一帧开始了”

在 H.264 中：

- `AUD` 的 `nal_unit_type = 9`
- 它是可选的
- 没有 `AUD`，码流仍然可以完全合法、完全可解码

本次软编码输出文件开头就能看到：

```text
00 00 00 01 09 ...
```

这里的 `09` 就是 `AUD`。

---

### 2.4 SPS 是什么

`SPS` 全称是 `Sequence Parameter Set`。

它描述的是一段视频序列的关键参数，例如：

- Profile
- Level
- 分辨率
- 参考帧相关信息
- 时序相关信息

如果没有 SPS，解码器通常不知道：

- 这路视频是什么规格
- 后面 slice 应该怎么解释

所以 `SPS` 属于“解码前必须知道的重要参数”。

---

### 2.5 PPS 是什么

`PPS` 全称是 `Picture Parameter Set`。

它比 SPS 更贴近图像层，通常描述：

- 熵编码方式
- 去块滤波相关设置
- slice 解码相关参数

可以简单理解成：

- `SPS`：更像“整段视频序列级别”的参数
- `PPS`：更像“图像/片级别”的参数

通常 `PPS` 要和 `SPS` 搭配使用。

---

### 2.6 IDR 是什么

`IDR` 全称是 `Instantaneous Decoder Refresh`。

在 H.264 里，`IDR slice` 是关键帧的一种强形式，`nal_unit_type = 5`。

它的特点是：

- 解码器从这里开始，可以较独立地恢复图像
- 播放器 seek、起播、丢包恢复时都很依赖它

可以把它理解成：

- `IDR` 是“适合播放器从这里开始进入画面”的关键帧

---

## 3. 这些概念之间的关系

可以用一张简化图来理解：

```text
.h264 裸流
  ├─ NAL #1 : SPS
  ├─ NAL #2 : PPS
  ├─ NAL #3 : IDR slice
  ├─ NAL #4 : non-IDR slice
  ├─ NAL #5 : non-IDR slice
  └─ ...
```

如果启用了 `AUD`，则可能变成：

```text
.h264 裸流
  ├─ NAL #1 : AUD
  ├─ NAL #2 : SPS
  ├─ NAL #3 : PPS
  ├─ NAL #4 : SEI
  ├─ NAL #5 : IDR slice
  ├─ NAL #6 : AUD
  ├─ NAL #7 : non-IDR slice
  └─ ...
```

再从“帧”的角度理解：

```text
Access Unit #1
  ├─ AUD
  ├─ SPS
  ├─ PPS
  ├─ SEI
  └─ IDR slice

Access Unit #2
  ├─ AUD
  └─ non-IDR slice
```

所以：

- `NAL` 是码流单位
- `Access Unit` 是一帧级别的逻辑集合
- `AUD` 是 Access Unit 的边界提示
- `SPS/PPS` 是解码参数
- `IDR` 是关键图像数据

---

## 4. 这次硬编与软编的实际差异

### 4.1 硬编码裸流头部

硬编码文件头部更接近：

```text
SPS -> PPS -> IDR
```

实际十六进制开头类似：

```text
00 00 00 01 67 ...
00 00 00 01 68 ...
00 00 00 01 65 ...
```

这是一种非常常见、也比较“朴素”的 Annex B 裸流组织方式。

`VLC` 对这种风格通常更容易接受。

---

### 4.2 软编码裸流头部

软编码初版使用了：

```cpp
repeat-headers=1:annexb=1:aud=1
```

因此文件开头更接近：

```text
AUD -> SPS -> PPS -> SEI -> IDR
```

实际能看到：

```text
00 00 00 01 09 ...   // AUD
00 00 00 01 67 ...   // SPS
00 00 00 01 68 ...   // PPS
00 00 01 06 ...      // SEI
```

其中：

- `09` 是 `AUD`
- `06` 是 `SEI`
- `SEI` 中还能看到 x264 的 user_data 文本

---

## 5. 为什么 ffplay 能播，而 VLC 最初不能播

这次的关键不是“有没有 H.264 数据”，而是：

- 这是 **裸 `.h264`**
- 没有 MP4/FLV/TS 这样的容器层
- 播放器必须自己判断码流边界、时序和参数头组织方式

`ffplay` 的特点：

- 本身就是 FFmpeg 系播放器
- 对原始 Annex B 裸流支持非常强
- 对 `AUD + SPS + PPS + SEI + slice` 这种组织方式兼容很好

`VLC` 的表现：

- 能播放很多 H.264 裸流
- 但对某些“前导 NAL 组合”更挑剔
- 本次实验中，对带 `AUD` 的软编码裸流兼容较差

也就是说：

- **码流合法，不代表所有播放器对裸流都同样宽容**
- **播放器差异在裸流场景下会被放大**

---

## 6. 最终定位结果

通过对比实验，已经确认：

### 初始软编码参数

```cpp
repeat-headers=1:annexb=1:aud=1
```

现象：

- `ffplay` 正常
- `VLC` 不正常

### 去掉 AUD 后

改为：

```cpp
repeat-headers=1:annexb=1
```

现象：

- `ffplay` 正常
- `VLC` 也正常

因此本次结论是：

> 当前工程里的软编码 `.h264` 裸流，与 `VLC` 的兼容性问题，主要由 `AUD` 引入。

---

## 7. 为什么去掉 AUD 后更像硬编码输出

去掉 `aud=1` 后，软编码输出的码流头部会更接近硬编码文件的风格：

- 没有多余的 `AUD`
- 更容易形成类似 `SPS -> PPS -> IDR` 的开头结构

这让软编码裸流在外观和播放器体验上更接近当前工程的硬编码输出。

这也是为什么：

- 硬编码裸流 `VLC` 一直能认
- 软编码去掉 `AUD` 后也能认

---

## 8. 当前工程建议

针对当前学习工程，建议如下：

### 8.1 对 `.h264` 裸流演示页

优先使用：

```cpp
repeat-headers=1:annexb=1
```

原因：

- 方便 H264 Analyzer 分析
- 兼容 `ffplay`
- 兼容 `VLC`
- 更接近当前硬编码实验结果

### 8.2 不建议默认打开 `aud=1`

原因：

- `AUD` 对本项目学习演示不是必需项
- 打开后会引入播放器兼容性差异
- 会让“编码是否成功”和“播放器是否兼容”混在一起，干扰调试判断

### 8.3 如果面向更通用的播放器验证

更稳的做法不是直接分发裸 `.h264`，而是：

- 再额外封装成 `.mp4`
- 或封装成 `.flv` / `.ts`

因为容器会补齐：

- 时长
- 时间戳
- 索引
- 轨道元数据

这样播放器兼容性通常会明显好于裸流。

---

## 9. 一句话总结

这次问题不是软编码失败，而是：

> `libx264` 软编码输出的裸 H.264 在开启 `aud=1` 时，码流前面多了 `AUD`，`ffplay` 可以正常处理，但 `VLC` 对这类裸流更挑剔；去掉 `aud=1` 后，码流组织更接近硬编码输出，`VLC` 即可正常播放。

---

## 10. 当前修复结论

当前工程已将软编码参数从：

```cpp
repeat-headers=1:annexb=1:aud=1
```

调整为：

```cpp
repeat-headers=1:annexb=1
```

该调整适合作为当前 `VideoSoftEncodeActivity` 的默认配置。
