# FFmpegAVTutorial 构建兼容性修改报告

## 背景

本次修改的起点是：工程在另一台电脑上可以正常编译运行，但在当前电脑上从 Gradle Sync、构建、安装到启动过程中连续暴露了多个环境兼容问题。

这并不代表原工程的业务代码有问题。更准确地说，原工程依赖了另一台开发环境中的一些隐式条件，例如较新的 Android Studio、较新的 AGP/Gradle、已安装的 NDK、本地 Kotlin/AndroidX 配置等。当前电脑的 Android Studio 支持范围、SDK/NDK 安装状态和 Gradle 配置更偏稳定版，因此这些隐式条件被逐个暴露出来。

本次处理目标是：保留工程现有功能和源码结构，将构建配置调整为更适合开源项目传播的显式配置，让新电脑 clone 后更容易 Sync、Build、Install、Run。

## 最终结果

当前工程已经在用户本机完成编译、安装和运行验证。

最终关键构建配置如下：

```text
Android Gradle Plugin: 8.10.1
Gradle Wrapper: 8.11.1
Kotlin Gradle Plugin: 2.1.21
compileSdk: 36
targetSdk: 36
minSdk: 24
NDK: 28.2.13676358
CMake: 3.22.1
Java target: 11
Kotlin JVM target: 11
ABI: arm64-v8a, armeabi-v7a
```

## 修改文件总览

本次主要修改以下文件：

```text
README.md
BUILD_MIGRATION_REPORT.md
app/build.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
```

另外，Android Studio 可能自动生成或修改 `.idea/gradle.xml`、`.idea/misc.xml`、`.idea/migrations.xml` 等 IDE 配置文件。这类文件属于本地 IDE 状态变化，不是本次核心构建逻辑修改。

## 问题 1：AGP 版本与当前 Android Studio 不兼容

### 现象

当前电脑编译时首先报错：

```text
The project is using an incompatible version (AGP 9.2.0-alpha08)
Latest supported version is AGP 8.10.1
```

### 原因

原工程使用：

```toml
agp = "9.2.0-alpha08"
```

`9.2.0-alpha08` 属于较新的 alpha 版本 AGP，需要较新的 Android Studio Canary/Preview 支持。当前电脑上的 Android Studio 最高只支持 AGP `8.10.1`，因此 Gradle Sync 阶段直接被拦截。

另一台电脑能正常运行，说明另一台电脑大概率安装了更新版本的 Android Studio，或者使用了可以支持 AGP 9 alpha 的 IDE/Gradle 环境。

### 修改

文件：`gradle/libs.versions.toml`

修改前：

```toml
agp = "9.2.0-alpha08"
```

修改后：

```toml
agp = "8.10.1"
```

### 为什么这样改

本项目已经开源，使用稳定范围内的 AGP 更适合其他开发者 clone 后直接打开。保留 AGP 9 alpha 虽然可用在新环境上，但会提高使用门槛，很多稳定版 Android Studio 用户会直接无法 Sync。

## 问题 2：Gradle Wrapper 与降级后的 AGP 不匹配

### 现象

原工程使用 Gradle Wrapper：

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
```

当 AGP 从 9.x 降到 8.10.1 后，如果继续使用 Gradle 9.x，容易出现新的版本兼容错误。

### 原因

AGP 和 Gradle 本身存在版本兼容矩阵。AGP 9.x 可以配套较新的 Gradle 9.x，而 AGP 8.10.1 更适合使用 Gradle 8.x。

### 修改

文件：`gradle/wrapper/gradle-wrapper.properties`

修改前：

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

修改后：

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
```

同时移除了旧 Gradle 9.4.1 分发包对应的 `distributionSha256Sum`，避免 Wrapper 下载 Gradle 8.11.1 时继续使用旧校验值导致校验失败。

### 为什么这样改

AGP 降级后，Gradle Wrapper 也要同步降到兼容区间。这样项目不依赖 Android Studio 本地 Gradle，而是使用仓库中明确指定的 Wrapper 版本，提高可复现性。

## 问题 3：compileSdk 使用了 AGP 9 风格 DSL

### 现象

原工程中 `compileSdk` 写法是：

```kotlin
compileSdk {
    version = release(36) {
        minorApiLevel = 1
    }
}
```

这个写法更偏向新版本 AGP 对 SDK Extension/Minor API 的 DSL 支持。降到 AGP 8.10.1 后，这种写法不再适合作为最稳妥的配置。

### 修改

文件：`app/build.gradle.kts`

修改后：

```kotlin
compileSdk = 36
```

### 为什么这样改

当前项目没有必须依赖 `36.1` minor API 的代码。使用 `compileSdk = 36` 更通用，AGP 8.10.1 可以稳定识别，也更符合普通 Android 项目的配置习惯。

## 问题 4：NDK 版本缺失

### 现象

构建时出现：

```text
Failed to install the following SDK components:
ndk;27.0.12077973 NDK (Side by side) 27.0.12077973

Update NDK version to 28.2.13676358 and sync project
```

### 原因

项目没有显式指定 `ndkVersion` 时，AGP/Android Studio 会根据本地环境和默认规则选择一个 NDK 版本。当前环境尝试使用或安装 `27.0.12077973`，但该组件安装失败。Android Studio 同时提示可以改用 `28.2.13676358`。

### 修改

文件：`app/build.gradle.kts`

新增：

```kotlin
ndkVersion = "28.2.13676358"
```

### 为什么这样改

这个项目有 native C++、CMake、FFmpeg/OpenSSL 静态库链接，NDK 版本是关键构建输入。显式固定 NDK 可以避免不同电脑各自选择默认版本，减少 “我这里能编，你那里不能编” 的情况。

## 问题 5：AndroidX 开关未启用

### 现象

构建时报错：

```text
Configuration `:app:debugRuntimeClasspath` contains AndroidX dependencies,
but the `android.useAndroidX` property is not enabled.
Set `android.useAndroidX=true` in the `gradle.properties` file and retry.
```

日志中列出了很多 AndroidX 依赖，例如：

```text
androidx.appcompat:appcompat:1.7.1
androidx.core:core-ktx:1.18.0
androidx.constraintlayout:constraintlayout:2.2.1
com.google.android.material:material:1.14.0
```

### 原因

项目依赖已经是 AndroidX 体系，但 `gradle.properties` 中缺少：

```properties
android.useAndroidX=true
```

AGP 在解析依赖时检测到 AndroidX 依赖，却发现项目没有声明启用 AndroidX，因此停止构建。

### 修改

文件：`gradle.properties`

新增：

```properties
android.useAndroidX=true
```

### 为什么这样改

项目已经使用 AppCompat、Core KTX、ConstraintLayout、Material Components，它们都属于 AndroidX 生态。启用 `android.useAndroidX=true` 是必须的显式配置。

没有启用 `android.enableJetifier=true`，是因为当前项目没有发现旧版 support library 依赖需要自动迁移。只打开必要开关即可。

## 问题 6：Kotlin 源码没有被编进 APK

### 现象

安装成功后启动崩溃：

```text
java.lang.RuntimeException: Unable to instantiate activity
ComponentInfo{com.lovelymaple.ffmpegavtutorial/com.lovelymaple.ffmpegavtutorial.MainActivity}

Caused by: java.lang.ClassNotFoundException:
Didn't find class "com.lovelymaple.ffmpegavtutorial.MainActivity"
```

### 原因

项目中 Activity 主要是 Kotlin 文件：

```text
app/src/main/java/com/lovelymaple/ffmpegavtutorial/MainActivity.kt
app/src/main/java/com/lovelymaple/ffmpegavtutorial/FFmpegInfoActivity.kt
app/src/main/java/com/lovelymaple/ffmpegavtutorial/FeatureDetailActivity.kt
```

但原始 Gradle 配置只启用了 Android Application 插件：

```kotlin
plugins {
    alias(libs.plugins.android.application)
}
```

没有显式启用 Kotlin Android 插件。结果是 `.kt` 源码没有被正常编译进 dex。APK 可以安装，但启动时系统根据 Manifest 查找 `MainActivity`，最终找不到类并崩溃。

### 修改

文件：`gradle/libs.versions.toml`

新增 Kotlin 插件版本：

```toml
kotlin = "2.1.21"
```

新增插件声明：

```toml
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

文件：`build.gradle.kts`

新增顶层插件注册：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

文件：`app/build.gradle.kts`

启用 Kotlin Android 插件：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
```

### 为什么这样改

只要项目中存在 Kotlin 源码，并且这些 Kotlin 类会被 Android Manifest、Java 代码或运行时反射使用，就必须显式启用 Kotlin Android 插件。这样 Gradle 才会创建 `compileDebugKotlin` 等任务，并把 Kotlin 输出合并进 APK。

这是开源项目中尤其重要的配置，不能依赖本机 IDE 缓存或历史导入状态。

## 问题 7：Java 与 Kotlin JVM target 不一致

### 现象

启用 Kotlin 插件后，出现新的编译错误：

```text
Execution failed for task ':app:compileDebugKotlin'.
Inconsistent JVM-target compatibility detected for tasks
'compileDebugJavaWithJavac' (11) and 'compileDebugKotlin' (21).
```

### 原因

项目 Java 编译配置是：

```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
```

但当前 Gradle JDK 可能是 JDK 21。Kotlin 插件在未显式指定 `jvmTarget` 时，会根据环境推导出 Kotlin JVM target `21`。于是 Java target 是 `11`，Kotlin target 是 `21`，两边不一致。

### 修改

文件：`app/build.gradle.kts`

新增 import：

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
```

新增 Kotlin 编译目标：

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
```

### 为什么这样改

Android 项目中 Java 和 Kotlin 编译目标应该保持一致。当前项目已经选择 Java 11，那么 Kotlin 也显式固定为 JVM 11，避免不同电脑因为 JDK 17、JDK 21 等环境差异导致编译结果不同。

## README 修改说明

除了构建配置修复，本次也扩展了 `README.md`。原始 README 只有项目标题：

```markdown
# FFmpegAVTutorial
```

现已补充为完整项目说明，包含：

- 项目定位。
- 当前功能说明。
- FFmpeg Runtime Info 页面介绍。
- JNI、CMake、FFmpeg/OpenSSL 静态库集成方式。
- 目录结构。
- 构建环境。
- 运行步骤。
- 推荐学习路线。
- 当前项目边界。
- 适合阅读人群。

这样做的原因是：项目已经开源，README 应该帮助外部开发者快速理解这个工程是什么、怎么构建、怎么运行、从哪里开始读源码、后续可以如何扩展。

## 原工程为什么在另一台电脑上没问题

这类问题在 Android 工程中很常见。另一台电脑可以运行，通常不是因为当前错误不存在，而是因为另一台电脑刚好满足了原工程没有写清楚的隐式条件。

可能包括：

- Android Studio 版本更新，支持 AGP `9.2.0-alpha08`。
- 本地已经安装了项目需要的 NDK。
- 本地 Gradle/JDK 配置刚好兼容。
- IDE 缓存或历史工程配置让 Kotlin 源码被正确识别。
- 项目曾经在那台电脑上经过 IDE 自动迁移，但迁移结果没有完整写回仓库。

开源项目更理想的状态是：不依赖某一台电脑的隐式配置，而是在仓库中显式声明构建所需版本和插件。本次修改就是把这些隐式条件尽量转成仓库内的显式配置。

## 修改后的构建链路

现在项目的构建链路大致是：

```text
Android Studio / Gradle Wrapper
        |
        v
Gradle 8.11.1
        |
        v
Android Gradle Plugin 8.10.1
        |
        +--> Kotlin Android Plugin 2.1.21
        |
        +--> compileSdk 36 / targetSdk 36 / minSdk 24
        |
        +--> NDK 28.2.13676358 + CMake 3.22.1
        |
        +--> Java target 11 + Kotlin JVM target 11
        |
        v
APK: Java/Kotlin classes + native ffmpegavtutorial library
```

## 后续建议

1. 保留当前 AGP `8.10.1` 方案，适合稳定版 Android Studio 用户。
2. 如果未来确实需要 AGP 9.x 特性，可以单独创建升级分支，并在 README 中明确要求 Android Studio Canary/Preview。
3. 建议把 `README.md` 中的构建环境作为项目标准环境，后续升级 AGP、Gradle、Kotlin、NDK 时一起更新。
4. 如果希望减少仓库中的 IDE 噪音，可以考虑确认 `.idea` 文件是否需要全部提交；开源 Android 项目通常只保留必要的代码与 Gradle 配置。
5. 当前 native 层只有一个空 struct warning，不影响运行；后续可以给 `InstanceHolder` 增加真实字段或移出 `extern "C"` 区块来清理 warning。

## 本次核心修改摘要

```text
1. AGP: 9.2.0-alpha08 -> 8.10.1
2. Gradle Wrapper: 9.4.1 -> 8.11.1
3. compileSdk: AGP 9 DSL -> compileSdk = 36
4. NDK: 显式固定为 28.2.13676358
5. AndroidX: 新增 android.useAndroidX=true
6. Kotlin: 新增 Kotlin Android 插件 2.1.21
7. JVM target: Kotlin 显式固定为 JVM 11，与 Java 11 对齐
8. README: 从单行标题扩展为完整开源项目说明
```
