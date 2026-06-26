# AAC 硬编码时间戳与 FFmpeg time_base 对照报告

## 1. 报告目的

这份报告用于整理本次关于 AAC 硬编码输出时间戳的分析结论，重点回答两个问题：

- `AacEncodeActivity` 中 `MediaCodec` 输出的 `ptsUs` 是怎么变化的
- 这种时间戳变化，能不能和 FFmpeg 的 `time_base * pts = 秒数` 对齐理解

相关页面：

- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/audio/AacEncodeActivity.kt`
- `app/src/main/cpp/AVEngine.cpp`

---

## 2. 当前分析对象

本次分析基于 `AacEncodeActivity` 的 AAC 硬编码日志。

关键日志片段如下：

```text
AAC output packet index=0 ptsUs=0 size=185
AAC output packet index=1 ptsUs=23219 size=186
AAC output packet index=2 ptsUs=46439 size=186
AAC output packet index=0 ptsUs=69659 size=186
AAC output packet index=1 ptsUs=92879 size=258
AAC output packet index=3 ptsUs=116099 size=241
AAC output packet index=0 ptsUs=139319 size=256
```

从日志可以直接看出：

- 输出时间戳是单调递增的
- 相邻 AAC 输出包的时间差大约稳定在 `23219 ~ 23220 us`

---

## 3. MediaCodec AAC 硬编码时间戳怎么理解

在 `MediaCodec` 输出侧，`AacEncodeActivity` 读到的是：

- `bufferInfo.presentationTimeUs`

这里的单位是：

- 微秒（us）

所以 `MediaCodec` 这套时间表达方式可以直接理解为：

```text
时间（秒） = ptsUs / 1_000_000
```

例如：

- `ptsUs = 23219`
- 对应秒数约为：

```text
23219 / 1_000_000 = 0.023219 秒
```

---

## 4. 为什么步进接近 23219us

AAC LC 一帧通常对应：

- `1024 samples`

当前页面参数是：

- `sample-rate = 44100 Hz`
- `channel-count = 2`

注意：

- 声道数影响一帧数据总量
- 但一帧 AAC 的时间长度主要取决于 `1024 / sample_rate`

因此一帧 AAC 的理论时长是：

```text
1024 / 44100 秒
= 0.0232199546 秒
= 23219.9546 us
```

这就解释了为什么日志里相邻时间戳大约是：

- `23219`
- `46439`
- `69659`
- `92879`

也就是：

- 每帧往前推进约 `23.22ms`

---

## 5. 为什么有时是 23219，有时更接近 23220

理论值不是整数，而是：

```text
23219.9546 us
```

但 `MediaCodec.BufferInfo.presentationTimeUs` 是整数微秒，所以编码器或系统输出时必须做整数化。

因此会出现：

- 有时差值是 `23219`
- 有时差值接近 `23220`

这属于正常现象，不代表时间线异常。

本质上它是在用整数微秒逼近：

```text
1024 / 44100 秒
```

---

## 6. FFmpeg 软编码的时间表达方式

在当前工程的 FFmpeg 软 AAC 编码里，native 层采用的是另一种表达方式：

- `time_base = {1, sample_rate}`
- `frame->pts = nextPts`
- `nextPts += frameSize`

对于 AAC LC：

- `sample_rate = 44100`
- `frameSize = 1024`

所以 FFmpeg 那边的 `pts` 序列通常是：

```text
0
1024
2048
3072
4096
5120
...
```

这里的 `pts` 单位不是微秒，而是：

- 采样点坐标

换算成秒时使用：

```text
时间（秒） = pts * time_base
          = pts / sample_rate
```

所以：

- 第 1 帧：`0 / 44100 = 0`
- 第 2 帧：`1024 / 44100 = 0.02321995 秒`
- 第 3 帧：`2048 / 44100 = 0.04643991 秒`

---

## 7. MediaCodec 与 FFmpeg 能不能对齐

答案是：

**可以。**

虽然两边使用的“时间坐标系”不同，但它们描述的是同一条音频时间线。

### MediaCodec

使用：

```text
ptsUs
```

时间换算：

```text
秒数 = ptsUs / 1_000_000
```

### FFmpeg

使用：

```text
pts + time_base
```

时间换算：

```text
秒数 = pts * time_base
     = pts / sample_rate
```

所以：

- `MediaCodec` 用“微秒坐标”表达时间
- `FFmpeg` 用“采样点坐标 + 时间基”表达时间

只要换算到秒，它们就是可比较、可对齐的。

---

## 8. 两种表达方式的直接对照

### 8.1 MediaCodec 硬编码日志

前几帧：

| 包序号 | ptsUs |
|---|---:|
| 1 | 0 |
| 2 | 23219 |
| 3 | 46439 |
| 4 | 69659 |
| 5 | 92879 |

换算成秒：

| 包序号 | 秒数 |
|---|---:|
| 1 | 0.000000 |
| 2 | 0.023219 |
| 3 | 0.046439 |
| 4 | 0.069659 |
| 5 | 0.092879 |

### 8.2 FFmpeg 软编码理论值

如果 `pts` 每帧递增 `1024`，且：

- `time_base = 1 / 44100`

则前几帧：

| 包序号 | pts | 秒数 |
|---|---:|---:|
| 1 | 0 | 0.000000 |
| 2 | 1024 | 0.02321995 |
| 3 | 2048 | 0.04643991 |
| 4 | 3072 | 0.06965986 |
| 5 | 4096 | 0.09287982 |

可以看到，两者在物理时间上是一致的。

---

## 9. 这是否意味着两边数值会完全相同

不一定完全相同，但可以对齐到同一条时间线。

原因：

- `MediaCodec` 直接给的是整数微秒
- `FFmpeg` 往往给的是“采样点坐标”
- 两边最终都需要换算到秒才是严格可比的统一尺度

因此：

- 数值表达形式不同
- 物理含义一致

可以理解成：

- `MediaCodec`：使用微秒做时间单位
- `FFmpeg`：使用采样点做时间单位，再用 `time_base` 解释

---

## 10. `AacEncodeActivity` 中的输入时间戳与输出时间戳关系

在 `AacEncodeActivity` 中，Java 层输入 AAC 硬编码器时，会自己维护：

```kotlin
presentationTimeUs += bytesToDurationUs(read, sampleRate, channelCount)
```

也就是说，输入侧在构造一条“PCM 时间线”。

而输出侧的 `bufferInfo.presentationTimeUs` 则体现为：

- 编码器根据输入时间线维护出来的输出时间线

本次硬编码日志表现出：

- 输出时间戳节奏稳定
- 与 AAC 每帧 1024 samples 的理论持续时间吻合

说明当前编码器时间线是健康的。

---

## 11. codec config 包不参与普通时间线分析

日志里第一条通常是：

```text
AAC output packet index=0 ptsUs=0 size=2 flags=2 codecConfig=true
```

这类包是：

- AAC 配置包
- 不是普通音频内容包

它通常用于：

- 提供 `AudioSpecificConfig`
- 帮助封装层或播放器初始化 AAC 解码器

所以在分析“真正的音频播放时间线”时，重点应看：

- `codecConfig=false`

的普通 AAC 输出包。

---

## 12. 工程上的统一理解方式

后续分析音频时间戳时，建议统一使用下面这套思路：

### 对 MediaCodec

```text
时间（秒） = ptsUs / 1_000_000
```

### 对 FFmpeg

```text
时间（秒） = pts * time_base
```

### 对 44100Hz AAC LC

每帧时长理论上约为：

```text
1024 / 44100 ≈ 23.21995ms
```

因此无论是：

- `MediaCodec` 的 `ptsUs`
- 还是 `FFmpeg` 的 `pts + time_base`

都应该体现出这条大约每帧推进 `23.22ms` 的时间线。

---

## 13. 结论

### 13.1 核心结论

1. `AacEncodeActivity` 中 `MediaCodec` 输出的 `ptsUs` 单位是微秒。
2. 当前日志中的时间戳步进约 `23219us`，正好对应 `44100Hz` 下 AAC 每帧 `1024 samples` 的理论时长。
3. `MediaCodec` 与 FFmpeg 的时间表达方式不同，但可以统一换算到秒。
4. `MediaCodec` 使用：

```text
秒数 = ptsUs / 1_000_000
```

5. FFmpeg 使用：

```text
秒数 = pts * time_base
```

6. 对于当前 AAC 场景，两者描述的是同一条物理时间线。

### 13.2 最值得记住的一句话

**MediaCodec 的 AAC 硬编码时间戳可以看作“已经换算成微秒的时间线”，而 FFmpeg 的时间戳更像“采样点坐标”，再乘以 `time_base` 才得到秒数；两者本质一致，只是坐标系不同。**
