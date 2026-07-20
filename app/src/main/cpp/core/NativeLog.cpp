#include "NativeLog.h"

#include <array>
#include <cstdio>

namespace {
constexpr size_t kLogBufferSize = 2048;
}

void NativeLog::D(const char *tag, const char *format, ...) {
    va_list args;
    va_start(args, format);
    Print(ANDROID_LOG_DEBUG, tag, format, args);
    va_end(args);
}

void NativeLog::I(const char *tag, const char *format, ...) {
    va_list args;
    va_start(args, format);
    Print(ANDROID_LOG_INFO, tag, format, args);
    va_end(args);
}

void NativeLog::W(const char *tag, const char *format, ...) {
    va_list args;
    va_start(args, format);
    Print(ANDROID_LOG_WARN, tag, format, args);
    va_end(args);
}

void NativeLog::E(const char *tag, const char *format, ...) {
    va_list args;
    va_start(args, format);
    Print(ANDROID_LOG_ERROR, tag, format, args);
    va_end(args);
}

void NativeLog::Print(int priority, const char *tag, const char *format, va_list args) {
    std::array<char, kLogBufferSize> buffer{};
    va_list argsCopy;
    va_copy(argsCopy, args);
    std::vsnprintf(buffer.data(), buffer.size(), format, argsCopy);
    va_end(argsCopy);

    __android_log_write(
        priority,
        tag != nullptr ? tag : kDefaultTag,
        buffer.data()
    );
}
