#include <jni.h>

#include <cstring>

#include "NativeLog.h"

extern "C" {
#include "libavutil/buffer.h"
#include "libavutil/frame.h"
#include "libavutil/imgutils.h"
#include "libavutil/pixfmt.h"
}

namespace {

void LogFrameState(const char *label, const AVFrame *frame) {
    if (frame == nullptr) {
        NATIVE_LOGI_TAG("AVFrameDemo", "%s: frame=null", label);
        return;
    }

    const int refCount = frame->buf[0] != nullptr ? av_buffer_get_ref_count(frame->buf[0]) : 0;
    const int writable = av_frame_is_writable(const_cast<AVFrame *>(frame));
    const int firstLuma = frame->data[0] != nullptr ? static_cast<int>(frame->data[0][0]) : -1;

    NATIVE_LOGI_TAG(
        "AVFrameDemo",
        "%s: frame=%p format=%d width=%d height=%d pts=%lld linesize=[%d,%d,%d] "
        "data=[%p,%p,%p] buf0=%p refCount=%d writable=%d firstY=%d",
        label,
        frame,
        frame->format,
        frame->width,
        frame->height,
        static_cast<long long>(frame->pts),
        frame->linesize[0],
        frame->linesize[1],
        frame->linesize[2],
        frame->data[0],
        frame->data[1],
        frame->data[2],
        frame->buf[0],
        refCount,
        writable,
        firstLuma
    );
}

void FillDemoYuv(AVFrame *frame, uint8_t yValue, uint8_t uValue, uint8_t vValue) {
    if (frame == nullptr || frame->data[0] == nullptr) {
        return;
    }

    for (int y = 0; y < frame->height; ++y) {
        std::memset(frame->data[0] + y * frame->linesize[0], yValue, frame->width);
    }

    const int chromaWidth = frame->width / 2;
    const int chromaHeight = frame->height / 2;
    for (int y = 0; y < chromaHeight; ++y) {
        std::memset(frame->data[1] + y * frame->linesize[1], uValue, chromaWidth);
        std::memset(frame->data[2] + y * frame->linesize[2], vValue, chromaWidth);
    }
}

void RunAvFrameDemo() {
    NATIVE_LOGI_TAG("AVFrameDemo", "========== AVFrame Demo ==========");
    NATIVE_LOGI_TAG("AVFrameDemo", "[1] av_frame_alloc creates an empty frame shell");
    AVFrame *frame = av_frame_alloc();
    if (frame == nullptr) {
        NATIVE_LOGE_TAG("AVFrameDemo", "av_frame_alloc failed");
        return;
    }
    LogFrameState("frame after alloc", frame);

    NATIVE_LOGI_TAG("AVFrameDemo", "[2] fill metadata before requesting buffer");
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = 320;
    frame->height = 180;
    frame->pts = 100;
    LogFrameState("frame after metadata", frame);

    NATIVE_LOGI_TAG("AVFrameDemo", "[3] av_frame_get_buffer allocates plane memory and AVBufferRef objects");
    int result = av_frame_get_buffer(frame, 32);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVFrameDemo", "av_frame_get_buffer failed: %d", result);
        av_frame_free(&frame);
        return;
    }
    LogFrameState("frame after get_buffer", frame);

    NATIVE_LOGI_TAG("AVFrameDemo", "[4] av_frame_make_writable ensures exclusive write access");
    result = av_frame_make_writable(frame);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVFrameDemo", "av_frame_make_writable(frame) failed: %d", result);
        av_frame_free(&frame);
        return;
    }
    FillDemoYuv(frame, 16, 128, 128);
    LogFrameState("frame after fill", frame);

    NATIVE_LOGI_TAG("AVFrameDemo", "[5] av_frame_clone creates a new frame header sharing the same buffers");
    AVFrame *alias = av_frame_clone(frame);
    if (alias == nullptr) {
        NATIVE_LOGE_TAG("AVFrameDemo", "av_frame_clone failed");
        av_frame_free(&frame);
        return;
    }
    LogFrameState("frame after clone", frame);
    LogFrameState("alias after clone", alias);

    NATIVE_LOGI_TAG("AVFrameDemo", "[6] av_frame_make_writable(alias) triggers copy-on-write when buffers are shared");
    result = av_frame_make_writable(alias);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVFrameDemo", "av_frame_make_writable(alias) failed: %d", result);
        av_frame_free(&alias);
        av_frame_free(&frame);
        return;
    }
    FillDemoYuv(alias, 90, 54, 240);
    alias->pts = 200;
    LogFrameState("frame after alias make_writable", frame);
    LogFrameState("alias after alias make_writable", alias);

    NATIVE_LOGI_TAG("AVFrameDemo", "[7] original and alias now hold independent writable buffers");
    NATIVE_LOGI_TAG(
        "AVFrameDemo",
        "frame->buf[0] == alias->buf[0] ? %s",
        frame->buf[0] == alias->buf[0] ? "true" : "false"
    );

    NATIVE_LOGI_TAG("AVFrameDemo", "[8] av_frame_unref drops buffer references but keeps the frame object reusable");
    av_frame_unref(alias);
    LogFrameState("alias after unref", alias);

    NATIVE_LOGI_TAG("AVFrameDemo", "[9] av_frame_free releases the frame shell itself");
    av_frame_free(&alias);
    LogFrameState("alias after free", alias);
    av_frame_free(&frame);
    LogFrameState("frame after free", frame);
    NATIVE_LOGI_TAG("AVFrameDemo", "========== AVFrame Demo Finished ==========");
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_runAvFrameDemo(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong nativeHandle
) {
    NATIVE_LOGI_TAG(
        "AVFrameDemo",
        "runAvFrameDemo nativeHandle=%lld",
        static_cast<long long>(nativeHandle)
    );
    RunAvFrameDemo();
}
