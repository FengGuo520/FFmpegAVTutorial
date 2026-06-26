# MediaCodec 时间戳专题报告

## 1. 报告目的

这份报告用于整理当前工程里关于 `MediaCodec` 时间戳的讨论结果，方便后续复习和排查问题。

本次分析重点覆盖：

- `MediaCodec.BufferInfo.presentationTimeUs` 的含义
- `AacEncodeActivity` 中输入时间戳与输出时间戳的关系
- `VideoEncodeActivity` 中视频输出时间戳的来源
- 为什么裸 `.aac` / `.h264` 文件通常不显式保存时间戳
- 为什么 FLV / RTMP 封装阶段必须真正消费时间戳
- 为什么停止编码时要发送 EOS 并继续 drain

相关页面：

- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/audio/AacEncodeActivity.kt`
- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/basic/VideoEncodeActivity.kt`
- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/container/LiveFlvMuxActivity.kt`
- `app/src/main/java/com/lovelymaple/ffmpegavtutorial/container/RtmpPushActivity.kt`

---

## 2. `presentationTimeUs` 是什么

`presentationTimeUs` 表示：

- 当前输入包或输出包在媒体时间线上的展示时间
- 单位是微秒（us）

对 `MediaCodec` 来说，时间戳不是“可有可无的附属信息”，而是媒体时间线的一部分。

在输出侧，`MediaCodec.BufferInfo` 里最关键的字段有：

- `offset`
- `size`
- `presentationTimeUs`
- `flags`

其中：

- `offset + size` 决定当前有效数据范围
- `presentationTimeUs` 决定当前数据在时间线上的位置
- `flags` 决定当前包是不是配置包、关键帧、EOS 包

---

## 3. 音频编码：`AacEncodeActivity` 的时间戳链路

### 3.1 输入侧怎么生成 PTS

在 `AacEncodeActivity` 中，硬编码主循环大致是：

1. `AudioRecord.read()` 采集一块 PCM
2. `queueInputBuffer(codec, pcmBuffer, read, presentationTimeUs)` 送入编码器
3. `presentationTimeUs += bytesToDurationUs(read, sampleRate, channelCount)`
4. `drainEncoder(...)` 取出 AAC 输出包

这里的 `presentationTimeUs` 是应用层自己累计出来的。

原因是：

- `AudioRecord` 只给原始 PCM 字节
- 不会自动附带“这一块 PCM 属于第几微秒”

所以应用层必须根据：

- 采样率
- 声道数
- PCM 位宽
- 本次读取的字节数

自行计算这块 PCM 持续了多久，再推进时间线。

### 3.2 为什么输出阶段看起来没再用到 PTS

`AacEncodeActivity` 最终输出的是：

- ADTS `.aac` 裸流文件

这类文件通常是：

- 一帧一帧顺序写出
- 每帧加 ADTS 头
- 不像 MP4 / FLV 那样显式保存每包 PTS 表

所以在当前页面里：

- 输入 PTS 是必要的
- 输出侧虽然可以通过 `bufferInfo.presentationTimeUs` 取到时间戳
- 但最终写文件时没有把这个时间戳显式落盘

也就是说：

- 这里的输出 PTS 主要用于调试和理解编码器行为
- 不直接参与 `.aac` 文件格式组织

---

## 4. 视频编码：`VideoEncodeActivity` 的时间戳链路

### 4.1 视频不是 `queueInputBuffer()` 模式

`VideoEncodeActivity` 走的是：

- Camera2
- MediaCodec Surface 输入模式

也就是说，视频不是手动：

```kotlin
queueInputBuffer(... ptsUs ...)
```

而是：

- 摄像头帧进入 encoder surface
- 编码器在输出侧给出 `bufferInfo.presentationTimeUs`

所以视频输出时间戳的来源不是 Java 层手动填写，而是：

- 上游图像帧时间线
- Surface buffer 时间线
- 编码器内部对视频输入时间线的维护

### 4.2 视频页虽然拿到了 PTS，但当前只用于调试

`VideoEncodeActivity` 当前页面的目标是：

- 把 H.264 / H.265 裸流写成 `.h264` / `.h265`

这类裸流文件通常只包含：

- NAL 单元
- 起始码
- 码流内容

通常不显式保存每帧 PTS/DTS 表。

所以当前页面里：

- 输出时间戳会打印到日志
- 但不会写入 `.h264` / `.h265` 文件本身

输出时间戳主要用于验证：

- 是否单调递增
- 相邻帧间隔是否符合目标帧率
- 关键帧节奏是否正常

---

## 5. 裸流页面 vs 封装页面：时间戳的使用差异

### 5.1 裸流页面

典型页面：

- `AacEncodeActivity`
- `VideoEncodeActivity`

特点：

- 可以拿到编码器输出时间戳
- 但最终文件本身通常不显式保存这些时间戳
- 时间戳更多用于调试和验证编码节奏

### 5.2 封装 / 推流页面

典型页面：

- `LiveFlvMuxActivity`
- `RtmpPushActivity`

特点：

- 输出时间戳必须真正参与业务
- 时间戳要传给 FLV muxer / RTMP 输出层
- 时间戳直接影响：
  - 音视频同步
  - 包顺序
  - 播放器识别时长
  - RTMP/FLV 时间线

所以：

- 裸流页：PTS 可以“只看不落盘”
- 封装页：PTS 必须“真正消费”

---

## 6. `codec config` 为什么要先于封装层启动

在音视频实时封装中，封装层不能只等普通媒体包，还必须先等 `codec config`。

### 6.1 视频侧

H.264 里最关键的是：

- SPS
- PPS

在 `MediaCodec` 输出格式里通常体现为：

- `csd-0`
- `csd-1`

封装层需要这些配置去写：

- FLV / RTMP 的 AVC sequence header
- MP4 的 `avcC`
- 其他容器的 extradata

### 6.2 音频侧

AAC 里最关键的是：

- `AudioSpecificConfig`

在 `MediaCodec` 输出格式里通常体现为：

- `csd-0`

封装层需要它去写：

- FLV 的 AAC sequence header
- MP4 的 `esds`
- 其他容器的音频配置头

### 6.3 工程结论

所以在 `LiveFlvMuxActivity` / `RtmpPushActivity` 里，必须先等：

- 视频 config 就绪
- 音频 config 就绪

然后才能真正 `openLiveFlvMuxer()` / `openRtmpPush()`。

这也是 `pendingPackets` 存在的原因：

- 先缓存早到的媒体包
- 等封装层启动后再 flush

---

## 7. EOS：为什么停止时不能直接停线程

### 7.1 音频也需要 EOS

对音频编码器来说，停止时不能只是：

- 不再读 PCM
- 直接释放编码器

因为编码器内部可能还有没吐出来的尾包。

所以需要：

- `queueInputBuffer(..., BUFFER_FLAG_END_OF_STREAM)`
- 然后继续 `drainEncoder(..., endOfStream = true)`

作用是：

- 告诉编码器没有更多输入了
- 让编码器把内部剩余数据完整吐出来

### 7.2 视频也需要 EOS

对 Surface 输入型视频编码器来说，停止时对应的是：

- `signalEndOfInputStream()`

之后也不能立刻释放编码器，而要继续 drain，直到输出包带上：

- `BUFFER_FLAG_END_OF_STREAM`

### 7.3 输出线程退出的真正条件

真正的退出条件不是“用户点了停止”，而是：

- 编码器最终输出了带 EOS 标记的最后一个包

这能避免：

- 尾帧丢失
- 尾部 AAC 包丢失
- 封装不完整

---

## 8. `dequeueOutputBuffer(..., 10_000)` 的正确理解

在 EOS 收尾阶段常见写法：

```kotlin
codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
```

这里的等待不是在等：

- 输入槽位

而是在等：

- 输出槽位里有没有新的编码结果可以取

也就是：

- `dequeueInputBuffer()`：等可写输入槽位
- `dequeueOutputBuffer()`：等可读输出槽位

收尾时给更长等待，是为了：

- 让编码器把内部尾包吐出来
- 不要太快退出 drain 循环

---

## 9. `VideoEncodeActivity` 日志结论

针对 H.264 硬编码页面，本次日志中出现了：

- `INFO_OUTPUT_FORMAT_CHANGED`
- `csd-0=29B`
- `csd-1=8B`
- 后续 `codecConfig=true size=37`

可以这样理解：

1. `INFO_OUTPUT_FORMAT_CHANGED`
   - 表示编码器输出格式正式确定
   - 说明 H.264 的 SPS/PPS 已就绪

2. `csd-0=29B, csd-1=8B`
   - 大概率对应 SPS 和 PPS

3. `codecConfig=true size=37`
   - 37 = 29 + 8
   - 很可能说明编码器又以一个 codec-config 包形式把 SPS+PPS 合并吐了一次

4. 后续普通视频包的 `deltaUs ≈ 33333`
   - 符合 30fps 节奏
   - 说明输出时间线正常

结论：

- `VideoEncodeActivity` 里虽然时间戳没有写入裸流文件
- 但日志已经足够证明编码器输出节奏正常

---

## 10. `AacEncodeActivity` 输入 PTS 全改成 0 的实验结论

本次做过一个实验：

```kotlin
codec.queueInputBuffer(inputIndex, 0, size, 0, 0)
```

也就是把每次输入 PTS 都强行写成 0。

### 10.1 预期风险

理论上，这会破坏输入时间线，因为应用层等于告诉编码器：

- 每一块 PCM 都发生在 0 us

### 10.2 实际观察结果

日志显示输出并没有全部变成 0，而是：

- 0
- 23219
- 46439
- 69659
- 92879
- ...

### 10.3 原因分析

在当前设备的 AAC 硬编码器实现上，编码器没有简单照抄输入的 0，而是根据：

- AAC 每帧固定 1024 samples
- 当前采样率 44100 Hz

自行重建了输出时间线。

理论时长：

```text
1024 / 44100 * 1,000,000 ≈ 23219.95 us
```

与日志中的 `23219 us` 步进完全吻合。

### 10.4 工程结论

说明：

- 当前这台设备的 AAC 硬编码器具备一定“自行维护输出时间线”的能力

但这不代表：

- 所有设备都这样
- 所有编码器都这样
- 可以长期依赖这种行为

正确做法仍然应该是：

- 应用层自己维护正确递增的输入 PTS
- 不要把输入 PTS 长期全部写成 0

---

## 11. 为什么“输入 PTS 不规范”有时看起来也能播

原因在于：

- `AacEncodeActivity` 写的是 ADTS `.aac`
- `VideoEncodeActivity` 写的是 Annex B `.h264/.h265`

这两种都是裸流文件。

裸流播放器通常可以按：

- AAC 固定帧样本数
- H.264/H.265 NAL 顺序

自行播放，而不是完全依赖显式容器时间戳。

因此：

- 时间线错误有时不会立刻表现成“文件不可播”

但一旦进入：

- FLV
- RTMP
- MP4
- 音视频同步

这类真正依赖时间戳的场景，问题就会明显暴露。

---

## 12. 最终总结

### 12.1 核心结论

1. `presentationTimeUs` 本质上属于媒体时间线的一部分。
2. 对音频 `queueInputBuffer()` 模式，输入 PTS 通常由应用层自己维护。
3. 对视频 Surface 输入模式，输出 PTS 来自上游图像帧时间线和编码器内部维护。
4. 裸 `.aac` / `.h264` / `.h265` 页面通常不会把输出 PTS 显式写入最终文件。
5. FLV / RTMP / MP4 这类封装或推流场景，必须真正消费时间戳。
6. 停止编码时必须发送 EOS 并继续 drain，直到编码器输出真正结束。
7. `codec config` 必须先于封装层启动，否则封装头和播放器初始化会出问题。

### 12.2 当前工程最值得记住的区别

- `AacEncodeActivity`
  - 输入 PTS 由应用层自己算
  - 输出 PTS 可见，但当前只用于调试

- `VideoEncodeActivity`
  - 输出 PTS 可见，但当前裸流文件不保存
  - 主要用来验证帧率和关键帧节奏

- `LiveFlvMuxActivity`
  - 输出 PTS 必须继续传给 FLV muxer
  - 是“时间戳真正参与业务”的第一阶段

- `RtmpPushActivity`
  - 输出 PTS 继续传给 RTMP/FLV 输出层
  - 是实时网络传输阶段

---

## 13. 建议的后续复习顺序

建议以后复习时按这个顺序看：

1. `MediaCodec.BufferInfo` 四个字段
2. `AacEncodeActivity` 里的输入 PTS 累计
3. `VideoEncodeActivity` 里的视频输出日志
4. `codec config` 与 `INFO_OUTPUT_FORMAT_CHANGED`
5. `LiveFlvMuxActivity` / `RtmpPushActivity` 里 PTS 如何继续传给封装层
6. EOS 与 drain 收尾流程

这样从“单编码器页”走到“实时封装/推流页”，时间戳这条线会最容易串起来。
