#include <jni.h>

#include <cerrno>
#include <cstring>

#include "NativeLog.h"

extern "C" {
#include "libavutil/buffer.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/pixfmt.h"
}

namespace {

constexpr int DEMO_FRAME_QUEUE_SIZE = 4;

struct DemoFrame {
    AVFrame *frame = nullptr;
    int serial = 0;
    double pts = 0.0;
    double duration = 0.0;
    int64_t pos = -1;
    int width = 0;
    int height = 0;
    int format = AV_PIX_FMT_NONE;
};

struct DemoFrameQueue {
    DemoFrame queue[DEMO_FRAME_QUEUE_SIZE];
    int rindex = 0;
    int windex = 0;
    int size = 0;
    int max_size = 0;
    int keep_last = 0;
    int rindex_shown = 0;
};

void LogFrameRef(const char *label, const AVFrame *frame) {
    if (frame == nullptr) {
        NATIVE_LOGI_TAG("FrameQueueDemo", "%s: frame=null", label);
        return;
    }
    const int refCount = frame->buf[0] != nullptr ? av_buffer_get_ref_count(frame->buf[0]) : 0;
    const int firstY = frame->data[0] != nullptr ? static_cast<int>(frame->data[0][0]) : -1;
    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "%s: frame=%p data0=%p buf0=%p refCount=%d pts=%lld firstY=%d",
        label,
        frame,
        frame->data[0],
        frame->buf[0],
        refCount,
        static_cast<long long>(frame->pts),
        firstY
    );
}

void LogQueueState(const char *label, const DemoFrameQueue *queue) {
    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "%s: rindex=%d windex=%d size=%d nb_remaining=%d keep_last=%d rindex_shown=%d",
        label,
        queue->rindex,
        queue->windex,
        queue->size,
        queue->size - queue->rindex_shown,
        queue->keep_last,
        queue->rindex_shown
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

void FrameQueueUnrefItem(DemoFrame *slot) {
    if (slot == nullptr) {
        return;
    }
    av_frame_unref(slot->frame);
    slot->serial = 0;
    slot->pts = 0.0;
    slot->duration = 0.0;
    slot->pos = -1;
    slot->width = 0;
    slot->height = 0;
    slot->format = AV_PIX_FMT_NONE;
}

int FrameQueueInit(DemoFrameQueue *queue, int maxSize, int keepLast) {
    std::memset(queue, 0, sizeof(DemoFrameQueue));
    queue->max_size = maxSize < DEMO_FRAME_QUEUE_SIZE ? maxSize : DEMO_FRAME_QUEUE_SIZE;
    queue->keep_last = keepLast ? 1 : 0;

    for (int i = 0; i < queue->max_size; ++i) {
        queue->queue[i].frame = av_frame_alloc();
        if (queue->queue[i].frame == nullptr) {
            return AVERROR(ENOMEM);
        }
    }
    return 0;
}

void FrameQueueDestroy(DemoFrameQueue *queue) {
    for (int i = 0; i < queue->max_size; ++i) {
        FrameQueueUnrefItem(&queue->queue[i]);
        av_frame_free(&queue->queue[i].frame);
    }
}

DemoFrame *FrameQueuePeek(DemoFrameQueue *queue) {
    return &queue->queue[(queue->rindex + queue->rindex_shown) % queue->max_size];
}

DemoFrame *FrameQueuePeekNext(DemoFrameQueue *queue) {
    return &queue->queue[(queue->rindex + queue->rindex_shown + 1) % queue->max_size];
}

DemoFrame *FrameQueuePeekLast(DemoFrameQueue *queue) {
    return &queue->queue[queue->rindex];
}

DemoFrame *FrameQueuePeekWritable(DemoFrameQueue *queue) {
    if (queue->size >= queue->max_size) {
        NATIVE_LOGI_TAG("FrameQueueDemo", "peek_writable: queue is full, producer would block in ffplay");
        return nullptr;
    }
    return &queue->queue[queue->windex];
}

void FrameQueuePush(DemoFrameQueue *queue) {
    if (++queue->windex == queue->max_size) {
        queue->windex = 0;
    }
    queue->size++;
}

void FrameQueueNext(DemoFrameQueue *queue) {
    if (queue->keep_last && !queue->rindex_shown) {
        queue->rindex_shown = 1;
        NATIVE_LOGI_TAG("FrameQueueDemo", "next: keep_last is enabled, mark current frame as shown but keep it for lastvp");
        return;
    }

    DemoFrame *slot = &queue->queue[queue->rindex];
    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "next: unref slot rindex=%d pts=%.3f serial=%d",
        queue->rindex,
        slot->pts,
        slot->serial
    );
    FrameQueueUnrefItem(slot);

    if (++queue->rindex == queue->max_size) {
        queue->rindex = 0;
    }
    queue->size--;
}

int FrameQueueNbRemaining(const DemoFrameQueue *queue) {
    return queue->size - queue->rindex_shown;
}

int FillSourceFrame(AVFrame *frame, int frameIndex, int64_t pts) {
    av_frame_unref(frame);
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = 160;
    frame->height = 90;
    frame->pts = pts;

    int result = av_frame_get_buffer(frame, 32);
    if (result < 0) {
        return result;
    }

    result = av_frame_make_writable(frame);
    if (result < 0) {
        return result;
    }

    FillDemoYuv(
        frame,
        static_cast<uint8_t>(16 + frameIndex * 30),
        static_cast<uint8_t>(128),
        static_cast<uint8_t>(128)
    );
    return 0;
}

int ProduceFrame(DemoFrameQueue *queue, AVFrame *scratch, int frameIndex, int serial) {
    DemoFrame *slot = FrameQueuePeekWritable(queue);
    if (slot == nullptr) {
        return AVERROR(EAGAIN);
    }

    const int64_t pts = frameIndex * 40;
    int result = FillSourceFrame(scratch, frameIndex, pts);
    if (result < 0) {
        return result;
    }

    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "produce #%d into slot=%d with av_frame_move_ref",
        frameIndex,
        queue->windex
    );
    LogFrameRef("source before move_ref", scratch);

    slot->serial = serial;
    slot->pts = pts / 1000.0;
    slot->duration = 0.040;
    slot->pos = frameIndex * 1000;
    slot->width = scratch->width;
    slot->height = scratch->height;
    slot->format = scratch->format;

    av_frame_move_ref(slot->frame, scratch);
    LogFrameRef("slot after move_ref", slot->frame);
    LogFrameRef("source after move_ref", scratch);

    FrameQueuePush(queue);
    LogQueueState("after push", queue);
    return 0;
}

void ConsumeOneFrame(DemoFrameQueue *queue, const char *reason) {
    if (FrameQueueNbRemaining(queue) <= 0) {
        NATIVE_LOGI_TAG("FrameQueueDemo", "%s: no readable frame", reason);
        return;
    }

    DemoFrame *last = FrameQueuePeekLast(queue);
    DemoFrame *current = FrameQueuePeek(queue);
    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "%s: last_pts=%.3f current_pts=%.3f current_serial=%d",
        reason,
        last->pts,
        current->pts,
        current->serial
    );
    LogFrameRef("current before next", current->frame);
    FrameQueueNext(queue);
    LogQueueState("after next", queue);
}

void RunFrameQueueDemo() {
    NATIVE_LOGI_TAG("FrameQueueDemo", "========== FrameQueue Demo ==========");
    NATIVE_LOGI_TAG("FrameQueueDemo", "This is a small ffplay-style FrameQueue without SDL mutex/cond blocking.");

    DemoFrameQueue queue;
    int result = FrameQueueInit(&queue, 3, 1);
    if (result < 0) {
        NATIVE_LOGE_TAG("FrameQueueDemo", "FrameQueueInit failed: %d", result);
        return;
    }
    LogQueueState("after init", &queue);

    AVFrame *scratch = av_frame_alloc();
    if (scratch == nullptr) {
        NATIVE_LOGE_TAG("FrameQueueDemo", "scratch av_frame_alloc failed");
        FrameQueueDestroy(&queue);
        return;
    }

    const int serial = 7;
    ProduceFrame(&queue, scratch, 0, serial);
    ProduceFrame(&queue, scratch, 1, serial);
    ProduceFrame(&queue, scratch, 2, serial);

    NATIVE_LOGI_TAG("FrameQueueDemo", "Try producing one more frame while full.");
    result = ProduceFrame(&queue, scratch, 3, serial);
    if (result == AVERROR(EAGAIN)) {
        NATIVE_LOGI_TAG("FrameQueueDemo", "producer must wait until consumer calls frame_queue_next");
    }

    ConsumeOneFrame(&queue, "consumer displays first frame");

    NATIVE_LOGI_TAG("FrameQueueDemo", "Try producing after keep_last only marked the first frame as shown.");
    result = ProduceFrame(&queue, scratch, 3, serial);
    if (result == AVERROR(EAGAIN)) {
        NATIVE_LOGI_TAG("FrameQueueDemo", "still full because keep_last keeps the old frame until the next next()");
    }

    ConsumeOneFrame(&queue, "consumer advances again and releases the previous frame");
    ProduceFrame(&queue, scratch, 3, serial);

    while (FrameQueueNbRemaining(&queue) > 0) {
        ConsumeOneFrame(&queue, "drain queue");
    }

    av_frame_free(&scratch);
    FrameQueueDestroy(&queue);
    NATIVE_LOGI_TAG("FrameQueueDemo", "========== FrameQueue Demo Finished ==========");
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_runFrameQueueDemo(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong nativeHandle
) {
    NATIVE_LOGI_TAG(
        "FrameQueueDemo",
        "runFrameQueueDemo nativeHandle=%lld",
        static_cast<long long>(nativeHandle)
    );
    RunFrameQueueDemo();
}
