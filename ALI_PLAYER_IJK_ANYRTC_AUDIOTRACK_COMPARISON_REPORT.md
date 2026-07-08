# AliPlayer / ijkplayer / anyRTC AudioTrack 架构对照报告

## 1. 报告目的

这份报告用于回答一个核心问题：

- 阿里播放器 SDK 内部对 `AudioTrack` 的使用方式，更像 `ijkplayer` 还是 `anyRTC / WebRTC`？

结论先写在前面：

**更像 `ijkplayer`，不是 `anyRTC / WebRTC`。**

原因是：

- `AliPlayer` 的播放核心更偏向 **native 内核主导**
- `AudioTrack` 更像是 **native 音频渲染模块通过 JNI 调用的系统后端**
- 而不是 Java 层先持有 `AudioTrack`，再由 Java 线程主动去拉 native PCM

---

## 2. 三种架构的核心区别

### 2.1 ijkplayer 风格

`ijkplayer` 的典型特点是：

- Java 层只暴露播放器控制接口
- 真正的播放循环、音视频同步、解码、渲染调度主要在 native 层
- 音频输出层会在 native 中抽象成一个后端模块
- 如果选用 `AudioTrack`，本质上是 native 通过 JNI 去构造并调用 Java 的 `AudioTrack`

可以把它理解为：

`native 播放器内核 -> native 音频渲染模块 -> JNI -> AudioTrack`

这里 `AudioTrack` 只是系统提供的音频输出设备接口，主导权仍在 native 内核手里。

### 2.2 anyRTC / WebRTC 风格

`anyRTC / WebRTC` 更像是另一种分工方式：

- Java 层更深度参与 Android 平台对象管理
- Java 层先持有 `AudioTrack`
- Java 层启动音频线程
- Java 线程循环调用 native 拿 PCM
- 再由 Java 调 `AudioTrack.write()`

可以把它理解为：

`native 产出 PCM -> Java 音频线程取数据 -> Java AudioTrack.write()`

这里 `AudioTrack` 的控制权更偏向 Java 层。

### 2.3 AliPlayer SDK 风格

从你主项目对阿里播放器的接入方式，以及阿里 SDK 的 so 特征来看，它更接近 `ijkplayer`：

- 业务层只调用 `AliPlayer` / `AliMediaPlayer`
- 业务层不直接创建 `AudioTrack`
- SDK 底层 so 中能看到 `AudioTrackRender`
- so 中还能看到 `android/media/AudioTrack`、`play`、`write`、`pause`、`flush`、`getMinBufferSize` 等 JNI 相关痕迹

这说明：

- 阿里 SDK 的 native 播放内核内部已经封装好了音频渲染器
- 该渲染器在底层通过 JNI 使用 `AudioTrack`
- 业务 Java 层并不直接参与 PCM 写出

---

## 3. 对照表

| 对比维度 | ijkplayer | AliPlayer SDK | anyRTC / WebRTC |
|---|---|---|---|
| 整体架构风格 | native 主导播放器内核 | native 主导播放器内核 | Java 平台层 + native 协同 |
| 业务层入口 | Java `IjkMediaPlayer` | Java `AliPlayer` / `AliMediaPlayer` | Java `WebRtcAudioTrack` / `Camera2Session` 等 |
| `AudioTrack` 谁创建 | native 通过 JNI 创建/控制 | 大概率 native 通过 JNI 创建/控制 | Java 层创建并持有 |
| 谁调用 `AudioTrack.write()` | native 经 JNI 驱动 | native 经 JNI 驱动 | Java 音频线程调用 |
| PCM 数据谁主导 | native 解码后送输出模块 | native 解码后送输出模块 | native 提供 PCM，Java 负责写出 |
| Java 层对音频渲染的直接控制 | 弱 | 弱 | 强 |
| Java 层主要职责 | 控制播放器、接收回调 | 控制播放器、接收回调 | 管理平台对象、参与线程调度 |
| 更像哪一类 | 传统播放器 | 传统播放器 | 实时音视频引擎 |

---

## 4. 你主项目中的实际情况

### 4.1 业务层调用方式

你主项目中业务层主要是在调用：

- `setDataSource(...)`
- `prepare()`
- `start()`
- `setSurface(...)`

也就是说，业务代码只是在控制阿里播放器实例，而不是自己处理音频渲染。

### 4.2 依赖形态

主项目依赖的是阿里完整播放器 SDK：

- `com.aliyun.sdk.android:AliyunPlayer:7.15.0-full-55524919`

`full` 版本通常意味着：

- 不只是 Java 接口层
- 还包含较完整的 native 播放能力和相关 so

### 4.3 so 中的证据

在阿里播放器 SDK 的 `libsaasCorePlayer.so` 中，可以看到与 `AudioTrack` 强相关的字符串：

- `AudioTrackRender`
- `android/media/AudioTrack`
- `getMinBufferSize`
- `setStereoVolume`
- `play`
- `write`
- `pause`
- `flush`
- `getPlaybackHeadPosition`

这些信息说明：

- SDK 内部存在名为 `AudioTrackRender` 的 native 音频渲染模块
- 这个模块会通过 JNI 调用 Java 的 `AudioTrack`
- 所以音频输出主导权不在你业务 Java 层，而在 SDK native 内核里

---

## 5. 为什么说它更像 ijkplayer

### 5.1 相同点

`AliPlayer SDK` 和 `ijkplayer` 的相似点主要有：

- 都是 **native 播放器内核主导**
- Java 层主要负责播放器控制和回调分发
- `AudioTrack` 只是系统音频输出后端
- native 内部会封装自己的音频渲染模块
- native 通过 JNI 去调用 `AudioTrack`

### 5.2 和 anyRTC 的关键差别

如果它更像 `anyRTC`，通常会看到下面这些特征：

- Java 层直接创建 `AudioTrack`
- Java 层有自己的播放线程
- Java 线程周期性向 native 拉取 PCM
- Java 层显式调用 `AudioTrack.write()`

但你这个阿里播放器接入中，没有看到业务层自己掌控这样的链路。

所以它不是 `anyRTC` 这种“Java 平台层更强主导”的模式，而是“播放器内核封装更深”的模式。

---

## 6. 三条典型数据流

### 6.1 ijkplayer

```text
Java 播放器接口
    ->
native 播放器内核
    ->
native 音频渲染模块
    ->
JNI 调用 AudioTrack
    ->
系统扬声器
```

### 6.2 AliPlayer SDK

```text
Java AliPlayer / 业务封装
    ->
native 播放器内核
    ->
native AudioTrackRender
    ->
JNI 调用 AudioTrack
    ->
系统扬声器
```

### 6.3 anyRTC / WebRTC

```text
native 音频处理核心
    ->
Java 音频线程向 native 取 PCM
    ->
Java AudioTrack.write()
    ->
系统扬声器
```

---

## 7. 最终结论

针对问题：

- **阿里 SDK 内部在 native 播放内核里封装了 `AudioTrack` 输出，更像 `ijkplayer` 还是 `anyRTC`？**

最终结论是：

**更像 `ijkplayer`。**

更准确一点说：

- 它属于 **native 主导的播放器架构**
- `AudioTrack` 是 **native 音频渲染模块通过 JNI 使用的系统后端**
- 不是 `anyRTC / WebRTC` 那种 **Java 线程主动持有并驱动 `AudioTrack.write()`** 的模式

---

## 8. 复习时的一句话记忆

可以直接记成下面这句：

**ijkplayer / AliPlayer：native 主导，AudioTrack 是后端。**

**anyRTC / WebRTC：Java 更深度持有平台对象，AudioTrack 常由 Java 线程驱动。**
