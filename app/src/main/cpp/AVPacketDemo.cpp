#include <jni.h>

#include <cstring>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/packet.h"
#include "libavutil/buffer.h"
#include "libavutil/error.h"
#include "libavutil/rational.h"
}

namespace {

void FillPayload(AVPacket *packet, uint8_t baseValue) {
    if (packet == nullptr || packet->data == nullptr) {
        return;
    }
    for (int i = 0; i < packet->size; ++i) {
        packet->data[i] = static_cast<uint8_t>(baseValue + i);
    }
}

void LogPacketState(const char *label, const AVPacket *packet) {
    if (packet == nullptr) {
        NATIVE_LOGI_TAG("AVPacketDemo", "%s: packet=null", label);
        return;
    }

    const int refCount = packet->buf != nullptr ? av_buffer_get_ref_count(packet->buf) : 0;
    const int firstByte = packet->data != nullptr && packet->size > 0
        ? static_cast<int>(packet->data[0])
        : -1;

    NATIVE_LOGI_TAG(
        "AVPacketDemo",
        "%s: pkt=%p data=%p size=%d buf=%p refCount=%d stream=%d pts=%lld dts=%lld "
        "duration=%lld pos=%lld flags=0x%x side_data=%d firstByte=%d",
        label,
        packet,
        packet->data,
        packet->size,
        packet->buf,
        refCount,
        packet->stream_index,
        static_cast<long long>(packet->pts),
        static_cast<long long>(packet->dts),
        static_cast<long long>(packet->duration),
        static_cast<long long>(packet->pos),
        packet->flags,
        packet->side_data_elems,
        firstByte
    );
}

void LogSideData(const char *label, const AVPacket *packet) {
    if (packet == nullptr || packet->side_data_elems <= 0) {
        NATIVE_LOGI_TAG("AVPacketDemo", "%s: no side data", label);
        return;
    }

    for (int i = 0; i < packet->side_data_elems; ++i) {
        const AVPacketSideData &sideData = packet->side_data[i];
        const int firstByte = sideData.data != nullptr && sideData.size > 0
            ? static_cast<int>(sideData.data[0])
            : -1;
        NATIVE_LOGI_TAG(
            "AVPacketDemo",
            "%s: side[%d] type=%d(%s) size=%zu firstByte=%d",
            label,
            i,
            sideData.type,
            av_packet_side_data_name(sideData.type),
            sideData.size,
            firstByte
        );
    }
}

void RunAvPacketDemo() {
    NATIVE_LOGI_TAG("AVPacketDemo", "========== AVPacket Demo ==========");
    NATIVE_LOGI_TAG("AVPacketDemo", "[1] av_packet_alloc creates an empty packet shell");

    AVPacket *packet = av_packet_alloc();
    if (packet == nullptr) {
        NATIVE_LOGE_TAG("AVPacketDemo", "av_packet_alloc failed");
        return;
    }
    LogPacketState("packet after alloc", packet);

    NATIVE_LOGI_TAG("AVPacketDemo", "[2] av_new_packet allocates refcounted compressed payload");
    int result = av_new_packet(packet, 16);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVPacketDemo", "av_new_packet failed: %d", result);
        av_packet_free(&packet);
        return;
    }
    FillPayload(packet, 0x10);
    packet->stream_index = 2;
    packet->pts = 90000;
    packet->dts = 87000;
    packet->duration = 3000;
    packet->pos = 123456;
    packet->flags = AV_PKT_FLAG_KEY;
    LogPacketState("packet after new_packet + metadata", packet);

    NATIVE_LOGI_TAG("AVPacketDemo", "[3] av_packet_new_side_data attaches extra per-packet metadata");
    uint8_t *sideData = av_packet_new_side_data(packet, AV_PKT_DATA_NEW_EXTRADATA, 4);
    if (sideData != nullptr) {
        sideData[0] = 0xAA;
        sideData[1] = 0xBB;
        sideData[2] = 0xCC;
        sideData[3] = 0xDD;
    }
    LogPacketState("packet after side_data", packet);
    LogSideData("packet side_data", packet);

    NATIVE_LOGI_TAG("AVPacketDemo", "[4] av_packet_ref shares payload through AVBufferRef");
    AVPacket *shared = av_packet_alloc();
    if (shared == nullptr) {
        NATIVE_LOGE_TAG("AVPacketDemo", "shared av_packet_alloc failed");
        av_packet_free(&packet);
        return;
    }
    result = av_packet_ref(shared, packet);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVPacketDemo", "av_packet_ref failed: %d", result);
        av_packet_free(&shared);
        av_packet_free(&packet);
        return;
    }
    LogPacketState("packet after ref", packet);
    LogPacketState("shared after ref", shared);
    LogSideData("shared side_data after ref", shared);

    NATIVE_LOGI_TAG("AVPacketDemo", "[5] av_packet_make_writable(shared) triggers copy-on-write");
    result = av_packet_make_writable(shared);
    if (result < 0) {
        NATIVE_LOGE_TAG("AVPacketDemo", "av_packet_make_writable failed: %d", result);
        av_packet_free(&shared);
        av_packet_free(&packet);
        return;
    }
    FillPayload(shared, 0x70);
    shared->pts = 180000;
    shared->dts = 177000;
    LogPacketState("packet after shared make_writable", packet);
    LogPacketState("shared after make_writable + rewrite", shared);
    NATIVE_LOGI_TAG(
        "AVPacketDemo",
        "packet->buf == shared->buf ? %s",
        packet->buf == shared->buf ? "true" : "false"
    );

    NATIVE_LOGI_TAG("AVPacketDemo", "[6] av_packet_clone is av_packet_alloc + av_packet_ref");
    AVPacket *clone = av_packet_clone(packet);
    if (clone == nullptr) {
        NATIVE_LOGE_TAG("AVPacketDemo", "av_packet_clone failed");
        av_packet_free(&shared);
        av_packet_free(&packet);
        return;
    }
    LogPacketState("packet after clone", packet);
    LogPacketState("clone after clone", clone);

    NATIVE_LOGI_TAG("AVPacketDemo", "[7] av_packet_move_ref transfers ownership and clears source");
    AVPacket *moved = av_packet_alloc();
    if (moved == nullptr) {
        NATIVE_LOGE_TAG("AVPacketDemo", "moved av_packet_alloc failed");
        av_packet_free(&clone);
        av_packet_free(&shared);
        av_packet_free(&packet);
        return;
    }
    av_packet_move_ref(moved, shared);
    LogPacketState("shared after move_ref", shared);
    LogPacketState("moved after move_ref", moved);

    NATIVE_LOGI_TAG("AVPacketDemo", "[8] av_packet_rescale_ts converts timestamps between time bases");
    AVRational srcTimeBase = {1, 90000};
    AVRational dstTimeBase = {1, 1000};
    av_packet_rescale_ts(moved, srcTimeBase, dstTimeBase);
    LogPacketState("moved after rescale 1/90000 -> 1/1000", moved);

    NATIVE_LOGI_TAG("AVPacketDemo", "[9] av_packet_unref drops payload refs but keeps packet shell reusable");
    av_packet_unref(packet);
    LogPacketState("packet after unref", packet);
    LogPacketState("clone still holds packet's old payload", clone);

    NATIVE_LOGI_TAG("AVPacketDemo", "[10] av_packet_free releases packet shells and remaining refs");
    av_packet_free(&moved);
    av_packet_free(&clone);
    av_packet_free(&shared);
    av_packet_free(&packet);
    LogPacketState("packet after free", packet);
    NATIVE_LOGI_TAG("AVPacketDemo", "========== AVPacket Demo Finished ==========");
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_runAvPacketDemo(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong nativeHandle
) {
    NATIVE_LOGI_TAG(
        "AVPacketDemo",
        "runAvPacketDemo nativeHandle=%lld",
        static_cast<long long>(nativeHandle)
    );
    RunAvPacketDemo();
}
