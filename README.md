# FFmpegAVTutorial

FFmpegAVTutorial 是一个面向 Android 平台的 FFmpeg API 学习工程。项目目标不是直接提供一个完整播放器，而是把 Android App、JNI、CMake、FFmpeg 静态库和常见音视频处理概念串起来，帮助学习者从“能把 FFmpeg 集成进 APK”开始，逐步理解媒体探测、读包、解码、渲染、音频输出等模块应该如何拆分和演进。

当前工程已经内置 Android 端可运行的基础界面，并通过 JNI 调用 native 层 FFmpeg API，展示 FFmpeg 运行时版本、编译配置、协议、封装格式、解码器和编码器能力。首页还整理了一组音视频学习主题，用作后续扩展真实 demo 的目录骨架。

## 项目定位

- Android FFmpeg API 入门与实验工程。
- 演示 Kotlin/Java 通过 JNI 调用 C++ native 层。
- 演示 Android CMake 链接 FFmpeg/OpenSSL 预编译静态库。
- 提供 FFmpeg runtime info 页面，用于确认当前 APK 中集成的 FFmpeg 能力。
- 提供音视频学习目录，覆盖基础处理、视频渲染、音频渲染等方向。

## 当前功能

### 1. FFmpeg Runtime Info

入口：App 首页的 `OpenGL Version` 条目。

虽然条目名目前仍叫 `OpenGL Version`，实际页面主要用于展示 FFmpeg 运行时信息。native 层会调用 FFmpeg API 读取：

- `libavutil` / `libavcodec` / `libavformat` 版本信息。
- FFmpeg license 信息。
- FFmpeg configuration 编译参数。
- input protocols / output protocols。
- demuxers / muxers。
- video/audio/subtitle decoders。
- video/audio/subtitle encoders。

Android 页面会把 native 层返回的纯文本解析为分组卡片，按 Runtime、Protocols、Formats、Decoders、Encoders 和 Raw Output 展示。Raw Output 保留完整原始文本，便于学习、调试和对照 FFmpeg API 输出。

### 2. 教程目录页面

首页通过 `FeatureCatalog` 维护学习条目，当前分为三组：

- 音视频基础：Custom Thread、Movie Prober、Read Packet、Decode Packet、Custom Decoder。
- 视频渲染：Core Animation/Core Graphics/Core Media、Legacy OpenGL、Modern OpenGL、Modern OpenGL(Rectangle Texture)、Metal。
- 音频渲染：AudioUnit、AudioQueue。

其中 FFmpeg Runtime Info 已经接入真实 native 能力，其余条目目前是说明型详情页，用来描述模块职责、学习重点和后续实践建议。这个设计适合把每个音视频主题逐步补成独立 demo。

### 3. JNI 最小链路

工程保留了一个最小 JNI 示例：

- Kotlin Activity 声明 `external fun stringFromJNI(): String`。
- C++ 中实现 `Java_com_lovelymaple_ffmpegavtutorial_MainActivity_stringFromJNI`。
- 返回 `"Hello from C++"`。

这个入口可以作为排查 native library 是否正确加载、JNI 方法签名是否匹配、CMake 是否正常编译的最小样例。

## 技术栈

- Kotlin / Java
- Android AppCompat、Material Components、ConstraintLayout
- ViewBinding
- JNI
- C++ / CMake
- FFmpeg static libraries
- OpenSSL static libraries
- Android NDK

## Android 与 Native 集成方式

### Java/Kotlin 层

主要代码位于：

- `app/src/main/java/com/lovelymaple/ffmpegavtutorial`
- `app/src/main/java/io/ffmpegtutotial/player/internal/NativeInstance.java`

`NativeInstance` 负责加载 native library：

```java
static {
    System.loadLibrary("ffmpegavtutorial");
}
```

并声明 native 方法：

```java
protected native long makeNativeInstance(NativeInstance instance);
public native String getInfo(long nativePtr);
```

`FFmpegInfoActivity` 创建 `NativeInstance`，调用 `getInfo()` 获取 native 层拼装好的 FFmpeg 信息，再解析成适合移动端浏览的分组 UI。

### C++ 层

主要代码位于：

- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/AVEngine.cpp`
- `app/src/main/cpp/CMakeLists.txt`

`AVEngine.cpp` 中引入 FFmpeg 头文件：

```cpp
extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
}
```

当前重点函数是 `BuildFFmpegInfo()`，它会调用：

- `av_version_info()`
- `avutil_version()` / `avcodec_version()` / `avformat_version()`
- `avutil_license()` / `avcodec_license()` / `avformat_license()`
- `avutil_configuration()` / `avcodec_configuration()` / `avformat_configuration()`
- `avio_enum_protocols()`
- `av_demuxer_iterate()` / `av_muxer_iterate()`
- `av_codec_iterate()`
- `av_codec_is_decoder()` / `av_codec_is_encoder()`

这些 API 覆盖了学习 FFmpeg 集成时最常见的第一步：确认库已经正确链接，并且运行时能力和预期一致。

### CMake 链接

`app/src/main/cpp/CMakeLists.txt` 定义 native shared library：

```cmake
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        AVEngine.cpp)
```

并导入预编译静态库：

- `libavcodec.a`
- `libavformat.a`
- `libavutil.a`
- `libswscale.a`
- `libswresample.a`
- `libssl.a`
- `libcrypto.a`

最终链接到 Android native library：

```cmake
target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        mediandk
        z
        avcodec
        avformat
        avutil
        swscale
        swresample
        ssl
        crypto)
```

## 目录结构

```text
FFmpegAVTutorial/
|-- app/
|   |-- build.gradle.kts
|   `-- src/main/
|       |-- AndroidManifest.xml
|       |-- cpp/
|       |   |-- AVEngine.cpp
|       |   |-- CMakeLists.txt
|       |   |-- native-lib.cpp
|       |   `-- lib/
|       |       |-- ffmpeg/
|       |       |   |-- include/
|       |       |   |-- arm64-v8a/
|       |       |   `-- armeabi-v7a/
|       |       `-- openssl/
|       |           |-- include/
|       |           |-- arm64-v8a/
|       |           `-- armeabi-v7a/
|       |-- java/
|       |   |-- com/lovelymaple/ffmpegavtutorial/
|       |   |   |-- MainActivity.kt
|       |   |   |-- FFmpegInfoActivity.kt
|       |   |   |-- FeatureCatalog.kt
|       |   |   `-- FeatureDetailActivity.kt
|       |   `-- io/ffmpegtutotial/player/internal/
|       |       `-- NativeInstance.java
|       `-- res/
|-- build.gradle.kts
|-- settings.gradle.kts
`-- gradle/libs.versions.toml
```

## 构建环境

建议使用 Android Studio 打开项目根目录 `FFmpegAVTutorial`。

当前配置：

- `minSdk`: 24
- `targetSdk`: 36
- `compileSdk`: 36
- CMake: 3.22.1
- NDK: `28.2.13676358`
- Java compatibility: 11
- ABI: `arm64-v8a`、`armeabi-v7a`
- Android Gradle Plugin: `8.10.1`
- Gradle Wrapper: `8.11.1`

如果 Android Studio 提示 AGP 版本不兼容，请确认当前 IDE 至少支持 AGP `8.10.1`，并重新执行 Gradle Sync。

## 如何运行

1. 使用 Android Studio 打开 `FFmpegAVTutorial`。
2. 等待 Gradle Sync 完成。
3. 确认已安装 Android NDK 与 CMake 3.22.1。
4. 连接真机或启动模拟器。
5. 运行 `app`。
6. 进入首页后点击 `OpenGL Version`。
7. 查看 FFmpeg Runtime Info 页面中的版本、协议、格式和编解码器列表。

也可以在命令行构建：

```bash
./gradlew assembleDebug
```

Windows PowerShell 下可使用：

```powershell
.\gradlew.bat assembleDebug
```

## 学习路线建议

这个工程适合按以下顺序阅读和扩展：

1. 先看 `app/build.gradle.kts`，理解 ABI、CMake、ViewBinding 等 Android 配置。
2. 再看 `app/src/main/cpp/CMakeLists.txt`，理解 FFmpeg/OpenSSL 静态库如何导入和链接。
3. 阅读 `NativeInstance.java`，理解 Java 层如何加载 native library、声明 native 方法。
4. 阅读 `AVEngine.cpp`，理解 JNI 方法如何落到 C++，以及如何调用 FFmpeg API。
5. 运行 FFmpeg Runtime Info 页面，确认当前 FFmpeg 库的编译能力。
6. 以 `FeatureCatalog.kt` 中的条目为路线，逐步补充真实 demo。

推荐后续扩展顺序：

1. Movie Prober：实现 `avformat_open_input`、`avformat_find_stream_info`，展示媒体流信息。
2. Read Packet：实现 `av_read_frame`，观察 `AVPacket`、`stream_index`、`pts`、`dts`。
3. Decode Packet：实现 `avcodec_send_packet` / `avcodec_receive_frame`。
4. Custom Decoder：封装解码器对象，管理 `AVCodecContext`、`AVPacket`、`AVFrame` 生命周期。
5. Video Render：先完成 YUV420P 到屏幕的最小渲染链路，再扩展 NV12、NV21、BGRA 等格式。
6. Audio Render：先跑通 PCM 输出，再加入重采样、缓冲队列和播放状态管理。

## 当前边界

- 当前已经接入真实 FFmpeg runtime info 查询。
- 媒体探测、读包、解码、视频渲染、音频渲染条目目前主要是说明页，还不是完整可运行 demo。
- `NativeInstance` 中保留了大量播放器/推流相关 native 方法草稿，当前大多处于注释状态，可作为后续模块拆分参考。
- 工程当前只配置了 `arm64-v8a` 和 `armeabi-v7a`，如果需要 x86/x86_64 模拟器运行，需要补充对应 ABI 的 FFmpeg/OpenSSL 静态库。
- `OpenGL Version` 条目名和当前页面实际功能不完全一致，后续可以重命名为 `FFmpeg Runtime Info`。

## 适合谁阅读

- 想在 Android 上学习 FFmpeg C API 的开发者。
- 想了解 JNI + CMake + native static libraries 集成方式的 Android 开发者。
- 正在搭建音视频播放器、转码器、推流器底层能力的学习者。
- 想把 FFmpeg 学习过程拆成多个小实验逐步完成的人。

## License

请根据你开源仓库实际采用的协议补充 License 文件或在此处声明许可证。
