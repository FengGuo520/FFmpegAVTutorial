# FFmpegAVTutorial 模块导入路线图

## 1. 文档目标

本报告用于指导将 `anyRTC-RTMP-OpenSource` 中与推流相关的核心能力，逐步导入当前 `FFmpegAVTutorial` 学习工程。

目标不是一次性搬运完整库，而是按模块拆解、逐步接入、逐步验证，最终形成一套适合学习、调试和扩展的 Android 音视频工程。

---

## 2. 当前工程现状

当前工程已经具备以下基础：

- Android 首页功能展示结构已经搭好
- `FFmpegInfo` 页面可以通过 JNI 展示 FFmpeg 版本与配置信息
- 已有 JNI 入口和 native 入口
- 已有 FFmpeg 与 OpenSSL 预编译库目录

当前主要代码位置：

- UI 层
  - `app/src/main/java/com/lovelymaple/ffmpegavtutorial/MainActivity.kt`
  - `app/src/main/java/com/lovelymaple/ffmpegavtutorial/FFmpegInfoActivity.kt`
  - `app/src/main/java/com/lovelymaple/ffmpegavtutorial/FeatureDetailActivity.kt`
- JNI 层
  - `app/src/main/java/io/ffmpegtutotial/player/internal/NativeInstance.java`
- Native 层
  - `app/src/main/cpp/AVEngine.cpp`
  - `app/src/main/cpp/CMakeLists.txt`

当前工程适合继续演进为“功能模块化的学习工程”，而不是直接改造成 another SDK demo。

---

## 3. 总体导入原则

导入 anyRTC 核心能力时，遵循以下原则：

1. 先搭框架，再接能力
2. 先做本地功能，再做网络推流
3. 一次只导入一个模块
4. 每个模块都必须独立可验证
5. 每个阶段都要求可编译、可运行、可观察、可回退

建议避免一次性导入以下内容：

- 整套 anyRTC UI
- 播放器模块
- 美颜、SEI、截图、屏幕采集等增强能力
- 多协议推流支持

第一阶段只保留最核心主链路：

`采集 -> 编码 -> 封装 -> 推流`

---

## 4. 分阶段导入路线图

## 阶段 0：工程骨架重构

### 目标

把当前工程从“单点 JNI 信息展示”升级为“可承载多模块的学习工程骨架”。

### 本阶段做什么

- 增加统一的 Java/Kotlin engine 入口
- 保留 `NativeInstance`，但弱化它的业务职责
- 把 `AVEngine.cpp` 从“功能堆积点”改成“native 总入口”
- 为后续采集、编码、封装、推流预留目录结构

### 验收标准

- 工程可正常编译
- `FFmpegInfo` 功能不回退
- 后续模块可以按统一方式挂接

---

## 阶段 1：Camera Preview

### 目标

先导入摄像头采集与本地预览，不立即接推流。

### 本阶段做什么

- 新增 `Camera Preview` 首页入口
- 新增独立预览页
- 支持前后摄切换
- 支持页面进入、退出、释放

### 验收标准

- 可打开摄像头预览
- 可切换前后摄
- 返回页面不崩

---

## 阶段 2：Audio Capture

### 目标

先把麦克风 PCM 采集跑通，不先做编码。

### 本阶段做什么

- 新增 `Audio Capture` 首页入口
- 采集 PCM 数据
- 页面展示采样率、声道数、缓冲长度、音量

### 验收标准

- 可以稳定收到 PCM 数据
- 可以观察音量或采样信息
- 不影响视频模块

---

## 阶段 3：H264 Encode

### 目标

把摄像头视频帧送进视频编码器，优先验证 H264 输出。

### 本阶段做什么

- 新增 `H264 Encode Demo`
- 输出 `.h264` 文件
- 显示分辨率、码率、关键帧信息

### 编码策略建议

建议先走 Android 硬编路线，再考虑 FFmpeg 软编对照实现。

优先顺序：

1. `MediaCodec` 硬编版
2. 后续再补 `FFmpeg/x264` 学习版

### 验收标准

- 能输出 H264 码流
- 能识别关键帧
- 文件可用于验证

---

## 阶段 4：AAC Encode

### 目标

把 PCM 音频编码为 AAC。

### 本阶段做什么

- 新增 `AAC Encode Demo`
- 输出 `.aac` 文件
- 展示采样率、声道、AAC 帧信息

### 验收标准

- 能稳定编码 AAC
- 文件可验证
- 音频模块可单独运行

---

## 阶段 5：FLV Mux

### 目标

使用 FFmpeg 完成 H264 + AAC 的 FLV 封装。

### 本阶段做什么

- 新增 `FLV Mux Demo`
- 本地生成 `.flv`
- 展示音视频包数量和时间戳信息

### 验收标准

- 生成的 FLV 文件可播放
- 音视频时间戳基本正常
- 封装逻辑可独立验证

---

## 阶段 6：RTMP Push

### 目标

在本地采集、编码、封装都稳定后，再接入 RTMP 网络推流。

### 本阶段做什么

- 新增 `RTMP Push Demo`
- 使用 FFmpeg 进行网络输出
- 展示连接中、连接成功、重连、断开等状态

### 验收标准

- 可以成功推到 RTMP 服务端
- 音视频都能发送
- 页面能观察推流状态

---

## 阶段 7：增强模块

主链路稳定后，再逐步导入增强能力：

- 自定义视频输入
- 自定义音频输入
- 屏幕采集
- 截图
- SEI
- 推流统计信息
- 重连策略
- 多协议扩展

这些内容不建议提前于主链路实现。

---

## 5. 推荐目录结构草案

## Java/Kotlin 层

```text
app/src/main/java/com/lovelymaple/ffmpegavtutorial/
├─ ui/
│  ├─ home/
│  │  ├─ MainActivity.kt
│  │  └─ FeatureCatalog.kt
│  ├─ info/
│  │  └─ FFmpegInfoActivity.kt
│  ├─ detail/
│  │  └─ FeatureDetailActivity.kt
│  ├─ preview/
│  │  └─ CameraPreviewActivity.kt
│  ├─ audio/
│  │  └─ AudioCaptureActivity.kt
│  ├─ video/
│  │  └─ VideoEncodeActivity.kt
│  ├─ mux/
│  │  └─ FlvMuxActivity.kt
│  └─ push/
│     └─ RtmpPushActivity.kt
├─ core/
│  ├─ engine/
│  │  ├─ FFmpegEngine.kt
│  │  ├─ FFmpegEngineImpl.kt
│  │  └─ NativeBridge.kt
│  ├─ model/
│  │  ├─ AudioConfig.kt
│  │  ├─ VideoConfig.kt
│  │  └─ PushConfig.kt
│  ├─ capture/
│  ├─ encode/
│  ├─ mux/
│  ├─ push/
│  ├─ render/
│  └─ util/
└─ widget/
   └─ SystemBarSpacer.kt
```

## Native 层

```text
app/src/main/cpp/
├─ CMakeLists.txt
├─ jni/
│  ├─ AVEngine.cpp
│  ├─ NativeBridge.cpp
│  └─ JniHelpers.cpp
├─ engine/
│  ├─ FFmpegEngineNative.cpp
│  └─ FFmpegEngineNative.h
├─ capture/
│  ├─ camera/
│  └─ audio/
├─ encode/
│  ├─ video/
│  │  ├─ VideoEncoder.cpp
│  │  ├─ VideoEncoder.h
│  │  ├─ MediaCodecVideoEncoder.cpp
│  │  └─ FFmpegVideoEncoder.cpp
│  └─ audio/
│     ├─ AudioEncoder.cpp
│     └─ AudioEncoder.h
├─ mux/
│  ├─ FlvMuxer.cpp
│  └─ FlvMuxer.h
├─ push/
│  ├─ RtmpPusher.cpp
│  └─ RtmpPusher.h
├─ render/
├─ common/
│  ├─ Log.cpp
│  ├─ Log.h
│  ├─ SafeQueue.h
│  ├─ PacketBuffer.h
│  └─ TimeUtils.h
└─ third_party/
   ├─ ffmpeg/
   └─ openssl/
```

---

## 6. 首页功能规划建议

首页建议长期保持“功能目录页”形态，每完成一个阶段，就新增一个真实入口。

建议的首页功能演进路径：

- FFmpeg Runtime Info
- Camera Preview
- Audio Capture
- H264 Encode Demo
- AAC Encode Demo
- FLV Mux Demo
- RTMP Push Demo
- Screen Capture Push
- Custom Video Frame Push

这样工程会自然演进成一本“可运行的音视频学习手册”。

---

## 7. 每阶段统一交付要求

每个阶段都按相同方式落地：

1. 首页增加一个入口
2. 增加一个独立 Activity
3. 增加一层 Java/Kotlin API
4. 增加一个独立 native 模块
5. 做最小闭环验证

这样能保证问题边界清晰，调试时容易定位到底是：

- UI 问题
- 权限问题
- JNI 问题
- 采集问题
- 编码问题
- 封装问题
- 网络问题

---

## 8. 下一步建议

最推荐立即开始的工作是：

### Step 1

完成“阶段 0 工程骨架重构”。

优先落地：

- `FFmpegEngine.kt`
- `FFmpegEngineImpl.kt`
- `NativeBridge.kt`
- native 侧 `engine/`、`capture/`、`encode/`、`mux/`、`push/` 空目录结构

### Step 2

开始“阶段 1 Camera Preview”。

优先落地：

- 首页新增 `Camera Preview`
- 新建 `CameraPreviewActivity`
- 先打通本地视频预览链路

---

## 9. 结论

当前工程非常适合走“模块化导入 anyRTC 核心能力”的路线。

推荐策略不是一次性搬运，而是：

`先重构骨架 -> 再导入采集 -> 再导入编码 -> 再导入封装 -> 最后接推流`

这样最终得到的不是 another demo，而是一套真正可控、可学、可扩展的 FFmpeg Android 学习工程。
