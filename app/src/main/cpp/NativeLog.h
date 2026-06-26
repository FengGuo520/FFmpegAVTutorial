#pragma once

#include <android/log.h>
#include <cstdarg>

class NativeLog final {
public:
    static constexpr const char *kDefaultTag = "FFmpegAVTutorial";

    static void D(const char *tag, const char *format, ...);
    static void I(const char *tag, const char *format, ...);
    static void W(const char *tag, const char *format, ...);
    static void E(const char *tag, const char *format, ...);

private:
    static void Print(int priority, const char *tag, const char *format, va_list args);
};

#define NATIVE_LOGD(...) NativeLog::D(NativeLog::kDefaultTag, __VA_ARGS__)
#define NATIVE_LOGI(...) NativeLog::I(NativeLog::kDefaultTag, __VA_ARGS__)
#define NATIVE_LOGW(...) NativeLog::W(NativeLog::kDefaultTag, __VA_ARGS__)
#define NATIVE_LOGE(...) NativeLog::E(NativeLog::kDefaultTag, __VA_ARGS__)

#define NATIVE_LOGD_TAG(tag, ...) NativeLog::D(tag, __VA_ARGS__)
#define NATIVE_LOGI_TAG(tag, ...) NativeLog::I(tag, __VA_ARGS__)
#define NATIVE_LOGW_TAG(tag, ...) NativeLog::W(tag, __VA_ARGS__)
#define NATIVE_LOGE_TAG(tag, ...) NativeLog::E(tag, __VA_ARGS__)
