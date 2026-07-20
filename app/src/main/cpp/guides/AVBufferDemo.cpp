#include <jni.h>

#include <cstring>
#include <string>

#include "NativeLog.h"

extern "C" {
#include "libavutil/buffer.h"
#include "libavutil/mem.h"
}

namespace {

void LogBufferState(const char *label, const AVBufferRef *ref) {
    if (ref == nullptr) {
        NATIVE_LOGI_TAG("AVBufferDemo", "%s: ref=null", label);
        return;
    }
    NATIVE_LOGI_TAG(
        "AVBufferDemo",
        "%s: ref=%p buffer=%p data=%p size=%zu refCount=%d writable=%d firstByte=%d",
        label,
        ref,
        ref->buffer,
        ref->data,
        ref->size,
        av_buffer_get_ref_count(ref),
        av_buffer_is_writable(ref),
        ref->size > 0 ? static_cast<int>(ref->data[0]) : -1
    );
}

void DemoCustomFree(void *opaque, uint8_t *data) {
    const char *tag = opaque != nullptr ? static_cast<const char *>(opaque) : "custom-free";
    NATIVE_LOGI_TAG("AVBufferDemo", "custom free callback called, opaque=%s data=%p", tag, data);
    av_free(data);
}

void RunAvBufferDemo() {
    NATIVE_LOGI_TAG("AVBufferDemo", "========== AVBuffer / RefCount Demo ==========");
    NATIVE_LOGI_TAG("AVBufferDemo", "[1] av_buffer_alloc");
    AVBufferRef *owner = av_buffer_alloc(16);
    if (owner == nullptr) {
        NATIVE_LOGE_TAG("AVBufferDemo", "av_buffer_alloc failed");
        return;
    }
    std::memset(owner->data, 0, owner->size);
    owner->data[0] = 7;
    LogBufferState("owner after alloc", owner);

    NATIVE_LOGI_TAG("AVBufferDemo", "[2] av_buffer_ref creates another reference to the same AVBuffer");
    AVBufferRef *alias = av_buffer_ref(owner);
    if (alias == nullptr) {
        NATIVE_LOGE_TAG("AVBufferDemo", "av_buffer_ref failed");
        av_buffer_unref(&owner);
        return;
    }
    LogBufferState("owner after ref", owner);
    LogBufferState("alias after ref", alias);
    NATIVE_LOGI_TAG("AVBufferDemo", "owner->buffer == alias->buffer ? %s", owner->buffer == alias->buffer ? "true" : "false");

    NATIVE_LOGI_TAG("AVBufferDemo", "[3] refcount > 1 means buffer is no longer writable by convention");
    NATIVE_LOGI_TAG(
        "AVBufferDemo",
        "owner writable=%d, alias writable=%d",
        av_buffer_is_writable(owner),
        av_buffer_is_writable(alias)
    );

    NATIVE_LOGI_TAG("AVBufferDemo", "[4] av_buffer_make_writable on alias triggers copy-on-write");
    int result = av_buffer_make_writable(&alias);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVBufferDemo", "av_buffer_make_writable failed: %d", result);
        av_buffer_unref(&alias);
        av_buffer_unref(&owner);
        return;
    }
    LogBufferState("owner after make_writable(alias)", owner);
    LogBufferState("alias after make_writable(alias)", alias);
    NATIVE_LOGI_TAG("AVBufferDemo", "owner->buffer == alias->buffer ? %s", owner->buffer == alias->buffer ? "true" : "false");

    NATIVE_LOGI_TAG("AVBufferDemo", "[5] modify alias data and verify owner keeps old data");
    alias->data[0] = 99;
    LogBufferState("owner after alias write", owner);
    LogBufferState("alias after alias write", alias);

    NATIVE_LOGI_TAG("AVBufferDemo", "[6] av_buffer_unref decreases reference count and nulls the pointer");
    av_buffer_unref(&alias);
    LogBufferState("alias after unref", alias);
    LogBufferState("owner after alias unref", owner);

    NATIVE_LOGI_TAG("AVBufferDemo", "[7] av_buffer_create wraps existing memory with a custom free callback");
    uint8_t *raw = static_cast<uint8_t *>(av_malloc(8));
    if (raw == nullptr) {
        NATIVE_LOGE_TAG("AVBufferDemo", "av_malloc for wrapped buffer failed");
        av_buffer_unref(&owner);
        return;
    }
    std::memset(raw, 1, 8);
    AVBufferRef *wrapped = av_buffer_create(raw, 8, DemoCustomFree, const_cast<char *>("wrapped-demo"), 0);
    if (wrapped == nullptr) {
        NATIVE_LOGE_TAG("AVBufferDemo", "av_buffer_create failed");
        av_free(raw);
        av_buffer_unref(&owner);
        return;
    }
    LogBufferState("wrapped", wrapped);

    NATIVE_LOGI_TAG("AVBufferDemo", "[8] av_buffer_ref on wrapped buffer");
    AVBufferRef *wrappedRef2 = av_buffer_ref(wrapped);
    LogBufferState("wrapped ref #1", wrapped);
    LogBufferState("wrapped ref #2", wrappedRef2);

    NATIVE_LOGI_TAG("AVBufferDemo", "[9] releasing wrapped references; last unref will invoke custom free callback");
    av_buffer_unref(&wrappedRef2);
    LogBufferState("wrapped ref #2 after unref", wrappedRef2);
    av_buffer_unref(&wrapped);
    LogBufferState("wrapped ref #1 after unref", wrapped);

    NATIVE_LOGI_TAG("AVBufferDemo", "[10] final owner unref releases the original allocated buffer");
    av_buffer_unref(&owner);
    LogBufferState("owner after final unref", owner);
    NATIVE_LOGI_TAG("AVBufferDemo", "========== AVBuffer / RefCount Demo Finished ==========");
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_runAvBufferDemo(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong nativeHandle
) {
    NATIVE_LOGI_TAG(
        "AVBufferDemo",
        "runAvBufferDemo nativeHandle=%lld",
        static_cast<long long>(nativeHandle)
    );
    RunAvBufferDemo();
}
