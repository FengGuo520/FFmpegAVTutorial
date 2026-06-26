# Video Soft Encode B-Frame Timestamp Report

## 1. 背景

当前工程的软视频编码页面是：

- [VideoSoftEncodeActivity.kt](D:/study/GitProject/FFmpegAVTutorial/app/src/main/java/com/lovelymaple/ffmpegavtutorial/basic/VideoSoftEncodeActivity.kt)

底层软编码实现位于：

- [AVEngine.cpp](D:/study/GitProject/FFmpegAVTutorial/app/src/main/cpp/AVEngine.cpp)

本次分析的目标是说明：

- 软编码打开 B 帧后，为什么 `AVPacket.pts` 与 `AVPacket.dts` 会不同
- 为什么输出包的 `pts` 看起来不是单调递增
- 为什么前面先送了很多帧，后面才开始连续吐包

## 2. 本次软编码配置

从日志可见，本次 `libx264` 打开的关键参数如下：

```text
open soft video encoder codec=libx264
size=1280x720
frameRate=30
bitrate=2000000
gop=60
iFrameInterval=2
maxBFrames=2
profile=baseline
timeBase=1/30
x264Params=repeat-headers=1:annexb=1:bframes=2:b-adapt=1:rc-lookahead=20
```

这些参数的含义是：

- `frameRate=30`
  说明编码时间基按 30fps 处理。
- `timeBase=1/30`
  说明 `pts=1` 表示 `1/30` 秒。
- `gop=60`
  表示 GOP 大小为 60 帧，对应 2 秒一个关键帧。
- `maxBFrames=2`
  表示允许最多 2 个 B 帧。
- `rc-lookahead=20`
  表示编码器会提前观察一段后续帧，再决定当前帧的编码策略与重排方式。

## 3. 输入帧时间戳现象

软编码送帧日志示例：

```text
soft video frame submit index=0 inputPtsUs=1918843494092 codecPts=57565305 deltaPts=...
soft video frame submit index=1 inputPtsUs=1918843594159 codecPts=57565308 deltaPts=3
soft video frame submit index=2 inputPtsUs=1918843660881 codecPts=57565310 deltaPts=2
```

这里的关键点：

- `inputPtsUs`
  来自 `ImageReader` 的 `image.timestamp / 1000`，是摄像头帧的绝对采集时间戳。
- `codecPts`
  是把 `inputPtsUs` 按 `timeBase=1/30` 换算后的结果。

换算关系：

```text
codecPts = inputPtsUs * 30 / 1_000_000
```

因此第一帧 `codecPts` 是一个很大的数，并不是异常，而是因为它使用的是绝对时间线，而不是“从 0 开始的相对录制时间线”。

## 4. 输出包日志现象

日志片段：

```text
soft video packet raw index=0 pts=57565305 dts=57565300
soft video packet raw index=1 pts=57565312 dts=57565303
soft video packet raw index=2 pts=57565308 dts=57565305
soft video packet raw index=3 pts=57565310 dts=57565308
```

从这里可以直接看出两个核心现象。

### 4.1 `pts != dts`

这说明编码器已经发生了帧重排。

- `PTS`
  是显示时间戳，表示这一帧应该在什么时候显示。
- `DTS`
  是解码时间戳，表示这一包应该按什么顺序送入解码器。

如果没有 B 帧，通常会看到：

```text
pts == dts
```

而当前日志里已经明显不是这样，所以可以确认：

- B 帧配置已经真正生效

### 4.2 输出包 `pts` 不是严格单调递增

例如：

```text
index=1 pts=57565312
index=2 pts=57565308
```

后输出的包，显示时间反而更早。

这不是错误，而是 B 帧场景下的正常现象，因为：

- 编码器输出顺序更接近解码顺序
- 显示顺序由 `pts` 决定
- B 帧依赖前后参考帧，编码器会调整包的吐出顺序

所以：

- `packet index` 不是显示顺序
- `pts` 才是显示顺序
- `dts` 更接近解码输入顺序

## 5. 为什么前面先送很多帧，后面才开始出包

从日志可见：

```text
frame submit index=0
...
frame submit index=21
packet raw index=0
```

原因是当前配置启用了：

- `bframes=2`
- `rc-lookahead=20`

编码器需要先缓存一部分输入帧，做参考帧选择和重排决策，因此不会像 `zerolatency` 模式那样“输入一帧就立刻输出一包”。

这是典型的 B 帧编码延迟现象。

可以理解为：

```text
输入帧
-> 编码器内部缓冲
-> lookahead 分析
-> 决定 I/P/B 排列
-> 按解码顺序输出包
```

## 6. 为什么 `duration=0`

当前页面输出的是裸 `.h264` 码流，不是 MP4、FLV 这类容器。

在裸 H.264 输出场景中，`libx264` 返回的 `AVPacket.duration` 经常是 0，这是常见现象，不代表时间戳无效。

真正播放器播放时长、帧间隔、节奏控制，更多依赖：

- `pts`
- `dts`
- 封装层时间基
- 或播放器根据帧率做推导

## 7. 本次结论

这次日志已经可以确认以下结论：

1. 软视频编码器已经启用了 B 帧。
2. `AVPacket.pts` 不再简单等于输入 `AVFrame.pts` 的线性输出顺序。
3. `AVPacket.pts != AVPacket.dts` 是 B 帧生效的直接证据。
4. 输出包的 `pts` 非单调递增，是帧重排的正常表现。
5. 前面先积压多帧、后面集中出包，是 `bframes + rc-lookahead` 带来的编码延迟。

## 8. 结合当前工程如何理解

在当前工程里，软视频编码链路是：

```text
Camera ImageReader
-> YUV/I420
-> writeSoftVideoFrame(...)
-> AVFrame.pts
-> avcodec_send_frame(...)
-> avcodec_receive_packet(...)
-> AVPacket.pts / AVPacket.dts
-> 裸 .h264 文件
```

当不开 B 帧时：

- 输出更接近实时
- `pts` 和 `dts` 常常相等
- `AVPacket.pts` 看起来更像直接跟着 `AVFrame.pts`

当打开 B 帧时：

- 编码器会缓存与重排
- `pts` 与 `dts` 分离
- 输出顺序与显示顺序分离

## 9. 一个直观的小示意

假设显示顺序是：

```text
I0  B1  B2  P3
```

那么编码器为了让 B 帧能参考后面的 P 帧，解码输出顺序可能更像：

```text
I0  P3  B1  B2
```

于是就会出现：

- 输出包顺序不是显示顺序
- `dts` 按解码顺序走
- `pts` 按显示顺序走

这正是本次日志里看到的现象本质。

## 10. 后续建议

如果后续是为了教学和观察时间线，建议增加两类可选模式：

1. 零延迟模式
   - `max_b_frames=0`
   - `tune=zerolatency`
   - 便于观察“输入帧 pts -> 输出包 pts”的直接关系

2. B 帧模式
   - 保留当前配置
   - 便于观察 `pts/dts` 分离与重排

这样同一个页面就能直观看到：

- 无 B 帧编码
- 有 B 帧编码

两种模式下时间戳行为的区别。

## 11. 对照版：零延迟模式 vs B 帧模式

下面把两种常见软编码模式并排对照。

### 11.1 配置对照

| 项目 | 零延迟无 B 帧模式 | 当前 B 帧模式 |
| --- | --- | --- |
| 目标 | 低延迟、实时输出 | 更高压缩效率、观察重排 |
| `max_b_frames` | `0` | `2` |
| `tune` | `zerolatency` | 不设置 `zerolatency` |
| `bframes` | `0` | `2` |
| `rc-lookahead` | `0` 或极小 | `20` |
| 输出延迟 | 很低 | 更高 |
| `pts` / `dts` 关系 | 常常相等 | 经常不同 |
| 输出顺序 | 更接近显示顺序 | 更接近解码顺序 |

### 11.2 日志现象对照

#### A. 零延迟无 B 帧模式

常见现象是：

```text
frame submit index=0 codecPts=100
packet raw index=0 pts=100 dts=100

frame submit index=1 codecPts=101
packet raw index=1 pts=101 dts=101
```

特点：

- 送一帧，很快就能收到一个输出包
- `packet.pts` 往往直接跟着 `frame.pts`
- `packet.dts` 通常和 `packet.pts` 一样
- 更容易理解“输入一帧，对应输出一包”

这类模式适合：

- 直播低延迟链路
- 教学时观察基础时间戳传递
- 不强调压缩率、只强调实时性

#### B. 当前 B 帧模式

本次日志现象更像这样：

```text
frame submit index=0 codecPts=57565305
frame submit index=1 codecPts=57565308
...
frame submit index=21 codecPts=57565346
packet raw index=0 pts=57565305 dts=57565300
packet raw index=1 pts=57565312 dts=57565303
packet raw index=2 pts=57565308 dts=57565305
```

特点：

- 前面会先缓存多帧
- 后面才开始吐包
- `packet.pts` 和 `packet.dts` 分离
- 输出包 `pts` 可能不是单调递增

这类模式适合：

- 文件编码
- 非极致低延迟场景
- 专门观察 B 帧重排和参考帧关系

### 11.3 时间线理解对照

#### 零延迟无 B 帧

可以近似理解成：

```text
输入帧顺序
I0 -> P1 -> P2 -> P3

输出包顺序
I0 -> P1 -> P2 -> P3

PTS
0  -> 1  -> 2  -> 3

DTS
0  -> 1  -> 2  -> 3
```

也就是：

- 显示顺序和解码顺序几乎一致
- `pts` 和 `dts` 基本同步

#### B 帧模式

可以近似理解成：

```text
显示顺序
I0 -> B1 -> B2 -> P3

编码/输出顺序
I0 -> P3 -> B1 -> B2

PTS
0  -> 3  -> 1  -> 2

DTS
0  -> 1  -> 2  -> 3
```

也就是：

- `PTS` 代表显示顺序
- `DTS` 代表解码输入顺序
- 包输出顺序不再等于显示顺序

### 11.4 为什么学习时建议两种模式都看

如果只看零延迟模式，容易形成一个印象：

- `AVFrame.pts` 进去
- `AVPacket.pts` 出来
- 两者几乎一一对应

但这只是“无重排”的简化情况。

如果再看 B 帧模式，就能理解真实视频编码里更完整的一面：

- 输入时间线和输出顺序不一定一致
- `pts` 与 `dts` 的分工不同
- 编码器可能为了压缩效率牺牲部分实时性

### 11.5 一句话总结

可以把两种模式记成下面这组对照：

- 零延迟模式：
  更像“边来边发”，`pts`/`dts` 常常一致。
- B 帧模式：
  更像“先看几帧再决定怎么排”，`pts`/`dts` 分离、存在重排。
