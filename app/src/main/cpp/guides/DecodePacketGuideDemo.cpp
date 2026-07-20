#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>

#include "DecodedFrameWriters.h"
#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_desc.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/pixdesc.h"
#include "libavutil/samplefmt.h"
}

namespace {

constexpr const char *kTag = "DecodePacketGuide";
constexpr int kMaxAudioFrames = 220;
constexpr int kMaxVideoFrames = 45;
constexpr int kMaxLoggedPackets = 90;

std::string ErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

const char *SafeString(const char *value, const char *fallback = "unknown") {
    return value != nullptr ? value : fallback;
}

std::string MediaTypeLabel(AVMediaType mediaType) {
    const char *name = av_get_media_type_string(mediaType);
    return name != nullptr ? name : "unknown";
}

std::string CodecLabel(AVCodecID codecId) {
    const AVCodecDescriptor *descriptor = avcodec_descriptor_get(codecId);
    if (descriptor != nullptr && descriptor->name != nullptr) {
        return descriptor->name;
    }
    return SafeString(avcodec_get_name(codecId));
}

std::string FormatTime(int64_t timestamp, AVRational timeBase) {
    if (timestamp == AV_NOPTS_VALUE || timeBase.num == 0 || timeBase.den == 0) {
        return "unknown";
    }
    std::ostringstream oss;
    oss << timestamp << " (" << std::fixed << std::setprecision(3)
        << timestamp * av_q2d(timeBase) << " s)";
    return oss.str();
}

std::string JoinPath(const char *dir, const std::string &name) {
    std::string result = dir != nullptr ? dir : "";
    if (!result.empty()) {
        const char last = result[result.size() - 1];
        if (last != '/' && last != '\\') {
            result += '/';
        }
    }
    result += name;
    return result;
}

void LogLongTextByChunk(const std::string &title, const std::string &text, size_t chunkSize = 1800) {
    NATIVE_LOGI_TAG(kTag, "%s begin", title.c_str());
    size_t offset = 0;
    int chunk = 0;
    while (offset < text.size()) {
        const size_t length = std::min(chunkSize, text.size() - offset);
        NATIVE_LOGI_TAG(kTag, "%s chunk#%d:\n%s", title.c_str(), chunk, text.substr(offset, length).c_str());
        offset += length;
        chunk++;
    }
    NATIVE_LOGI_TAG(kTag, "%s end chunks=%d", title.c_str(), chunk);
}

void AppendCoreApiModel(std::ostringstream &oss) {
    oss << "Core API model\n";
    oss << "1. av_read_frame reads compressed AVPacket from demuxer.\n";
    oss << "2. avcodec_send_packet queues compressed data into decoder.\n";
    oss << "3. avcodec_receive_frame pulls decoded AVFrame out.\n";
    oss << "4. AVERROR(EAGAIN) means this side cannot progress right now.\n";
    oss << "5. avcodec_send_packet(ctx, nullptr) flushes delayed frames at EOF.\n\n";
}

void AppendExperimentScope(std::ostringstream &oss) {
    oss << "Experiment scope\n";
    oss << "- decode audio into s16le packed PCM\n";
    oss << "- decode video into yuv420p raw frames\n";
    oss << "- log at most " << kMaxLoggedPackets << " target packets per stream\n";
    oss << "- stop after " << kMaxAudioFrames << " audio frames or "
        << kMaxVideoFrames << " video frames\n\n";
}

struct DecodeObservation {
    bool receiveBeforeSendEagain = false;
    bool packetThenEagain = false;
    bool packetThenMultipleFrames = false;
    bool multiplePacketsThenFrame = false;
    bool flushSent = false;
    bool flushProducedFrame = false;
    int64_t packetCount = 0;
    int64_t frameCount = 0;
    int64_t bytesWritten = 0;
    int64_t skippedNonTargetPackets = 0;
    int64_t flushFrameCount = 0;
    int packetsSinceLastFrame = 0;
    std::string stopReason = "decode loop completed";
};

struct DecodeTarget {
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    const AVCodec *decoder = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    int streamIndex = -1;
    AVStream *stream = nullptr;

    ~DecodeTarget() {
        if (packet != nullptr) {
            av_packet_free(&packet);
        }
        if (frame != nullptr) {
            av_frame_free(&frame);
        }
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        if (formatContext != nullptr) {
            avformat_close_input(&formatContext);
        }
    }
};

std::string OpenDecodeTarget(const char *path, AVMediaType mediaType, DecodeTarget &target) {
    int result = avformat_open_input(&target.formatContext, path, nullptr, nullptr);
    if (result < 0) {
        return "avformat_open_input failed: " + ErrorToString(result);
    }

    result = avformat_find_stream_info(target.formatContext, nullptr);
    if (result < 0) {
        return "avformat_find_stream_info failed: " + ErrorToString(result);
    }

    result = av_find_best_stream(target.formatContext, mediaType, -1, -1, &target.decoder, 0);
    if (result < 0) {
        return "no " + MediaTypeLabel(mediaType) + " stream: " + ErrorToString(result);
    }
    target.streamIndex = result;
    target.stream = target.formatContext->streams[target.streamIndex];

    target.codecContext = avcodec_alloc_context3(target.decoder);
    if (target.codecContext == nullptr) {
        return "avcodec_alloc_context3 failed";
    }

    result = avcodec_parameters_to_context(target.codecContext, target.stream->codecpar);
    if (result < 0) {
        return "avcodec_parameters_to_context failed: " + ErrorToString(result);
    }
    target.codecContext->pkt_timebase = target.stream->time_base;

    result = avcodec_open2(target.codecContext, target.decoder, nullptr);
    if (result < 0) {
        return "avcodec_open2 failed: " + ErrorToString(result);
    }

    target.packet = av_packet_alloc();
    target.frame = av_frame_alloc();
    if (target.packet == nullptr || target.frame == nullptr) {
        return "failed to allocate AVPacket or AVFrame";
    }

    return {};
}

std::string DescribeOpenTarget(const DecodeTarget &target) {
    std::ostringstream oss;
    oss << "container_format: "
        << SafeString(target.formatContext->iformat != nullptr ? target.formatContext->iformat->name : nullptr) << '\n';
    oss << "container_duration: "
        << FormatTime(target.formatContext->duration, AV_TIME_BASE_Q) << '\n';
    oss << "bit_rate: " << target.formatContext->bit_rate << '\n';
    oss << "stream_count: " << target.formatContext->nb_streams << '\n';
    oss << "stream_index: " << target.streamIndex << '\n';
    oss << "codec: " << CodecLabel(target.codecContext->codec_id) << '\n';
    oss << "decoder_name: " << SafeString(target.decoder != nullptr ? target.decoder->name : nullptr) << '\n';
    oss << "decoder_long_name: "
        << SafeString(target.decoder != nullptr ? target.decoder->long_name : nullptr) << '\n';
    oss << "time_base: " << target.stream->time_base.num << "/" << target.stream->time_base.den << '\n';
    if (target.codecContext->codec_type == AVMEDIA_TYPE_AUDIO) {
        char layout[128] = {0};
        av_channel_layout_describe(&target.codecContext->ch_layout, layout, sizeof(layout));
        oss << "sample_rate: " << target.codecContext->sample_rate << '\n';
        oss << "channels: " << target.codecContext->ch_layout.nb_channels << '\n';
        oss << "channel_layout: " << layout << '\n';
        oss << "decoder_sample_fmt: "
            << SafeString(av_get_sample_fmt_name(target.codecContext->sample_fmt)) << '\n';
    } else if (target.codecContext->codec_type == AVMEDIA_TYPE_VIDEO) {
        oss << "width: " << target.codecContext->width << '\n';
        oss << "height: " << target.codecContext->height << '\n';
        oss << "decoder_pixel_fmt: "
            << SafeString(av_get_pix_fmt_name(target.codecContext->pix_fmt)) << '\n';
    }
    return oss.str();
}

std::string ObservationText(const DecodeObservation &obs) {
    std::ostringstream oss;
    oss << "observed.receive_before_send_EAGAIN: " << (obs.receiveBeforeSendEagain ? "yes" : "no") << '\n';
    oss << "observed.send_one_packet_receive_EAGAIN: " << (obs.packetThenEagain ? "yes" : "no") << '\n';
    oss << "observed.send_one_packet_receive_multiple_frames: " << (obs.packetThenMultipleFrames ? "yes" : "no") << '\n';
    oss << "observed.send_multiple_packets_then_receive_frame: " << (obs.multiplePacketsThenFrame ? "yes" : "no") << '\n';
    oss << "observed.flush_send_null_packet: " << (obs.flushSent ? "yes" : "no") << '\n';
    oss << "observed.flush_produced_frame: " << (obs.flushProducedFrame ? "yes" : "no") << '\n';
    oss << "total_packets_sent: " << obs.packetCount << '\n';
    oss << "total_frames_received: " << obs.frameCount << '\n';
    oss << "bytes_written: " << obs.bytesWritten << '\n';
    oss << "skipped_non_target_packets: " << obs.skippedNonTargetPackets << '\n';
    oss << "flush_frame_count: " << obs.flushFrameCount << '\n';
    oss << "stop_reason: " << obs.stopReason << '\n';
    return oss.str();
}

std::string FrameDetails(const AVFrame *frame, AVMediaType mediaType) {
    std::ostringstream oss;
    if (mediaType == AVMEDIA_TYPE_AUDIO) {
        oss << "nb_samples=" << frame->nb_samples
            << " sample_fmt="
            << SafeString(av_get_sample_fmt_name(static_cast<AVSampleFormat>(frame->format)))
            << " sample_rate=" << frame->sample_rate
            << " channels=" << frame->ch_layout.nb_channels;
    } else {
        oss << "width=" << frame->width
            << " height=" << frame->height
            << " pix_fmt="
            << SafeString(av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format)));
    }
    return oss.str();
}

void AppendStreamSectionHeader(
    std::ostringstream &oss,
    const std::string &writerSummary,
    int maxFrames
) {
    oss << writerSummary << '\n';
    oss << "max_frames: " << maxFrames << '\n';
    oss << "max_logged_packets: " << kMaxLoggedPackets << '\n';
    oss << "decode_steps:\n";
    oss << "  - probe receive before send\n";
    oss << "  - read target packets from demuxer\n";
    oss << "  - send AVPacket into decoder\n";
    oss << "  - receive AVFrame until EAGAIN/EOF\n";
    oss << "  - send nullptr packet to flush delayed frames\n\n";
}

template <typename Writer>
std::string DecodeStreamToFile(
    const char *path,
    const char *outputDir,
    AVMediaType mediaType,
    const std::string &outputName,
    int maxFrames
) {
    /*
     * Video data flow in this guide:
     * 1. av_read_frame reads one compressed video packet from the container.
     *    At this point the data is still codec bitstream data such as H.264/H.265,
     *    not directly displayable pixel data.
     * 2. avcodec_send_packet pushes that compressed AVPacket into the decoder.
     * 3. avcodec_receive_frame pulls one decoded AVFrame out of the decoder.
     *    The frame is now raw image data, but its pixel format depends on the
     *    decoder output and is not guaranteed to already be yuv420p.
     * 4. VideoYuvWriter::WriteFrame uses sws_scale to convert the decoded frame
     *    into a normalized AV_PIX_FMT_YUV420P frame at the same resolution.
     * 5. av_image_copy_to_buffer packs the planar frame data into one continuous
     *    raw buffer, and that buffer is written to the .yuv output file.
     *
     * In short:
     * container packet -> compressed AVPacket -> decoded AVFrame
     * -> yuv420p conversion -> continuous raw bytes -> .yuv file
     */
    DecodeTarget target;
    std::ostringstream oss;
    oss << "===== Decode " << MediaTypeLabel(mediaType) << " =====\n";

    std::string error = OpenDecodeTarget(path, mediaType, target);
    if (!error.empty()) {
        oss << "skip: " << error << "\n\n";
        return oss.str();
    }

    oss << DescribeOpenTarget(target);

    const std::string outputPath = JoinPath(outputDir, outputName);
    Writer writer;
    error = writer.Open(target.codecContext, outputPath);
    if (!error.empty()) {
        oss << "ERROR: " << error << "\n\n";
        return oss.str();
    }
    AppendStreamSectionHeader(oss, writer.Summary(), maxFrames);

    DecodeObservation obs;
    int result = avcodec_receive_frame(target.codecContext, target.frame);
    if (result == AVERROR(EAGAIN)) {
        obs.receiveBeforeSendEagain = true;
        oss << "[0] receive before send -> EAGAIN. Decoder needs compressed AVPacket first.\n";
    } else {
        oss << "[0] receive before send -> " << ErrorToString(result) << '\n';
    }
    av_frame_unref(target.frame);

    bool stopReading = false;
    while (!stopReading && obs.frameCount < maxFrames) {
        result = av_read_frame(target.formatContext, target.packet);
        if (result == AVERROR_EOF) {
            oss << "\nRead loop reached EOF.\n";
            break;
        }
        if (result < 0) {
            oss << "\nRead loop error: " << ErrorToString(result) << '\n';
            break;
        }
        if (target.packet->stream_index != target.streamIndex) {
            obs.skippedNonTargetPackets++;
            av_packet_unref(target.packet);
            continue;
        }

        obs.packetCount++;
        obs.packetsSinceLastFrame++;
        const bool logPacket = obs.packetCount <= kMaxLoggedPackets;
        if (logPacket) {
            oss << "\n[" << obs.packetCount << "] av_read_frame -> packet"
                << " size=" << target.packet->size
                << " pts=" << FormatTime(target.packet->pts, target.stream->time_base)
                << " dts=" << FormatTime(target.packet->dts, target.stream->time_base)
                << '\n';
        }

        result = avcodec_send_packet(target.codecContext, target.packet);
        av_packet_unref(target.packet);
        if (result < 0) {
            oss << "  avcodec_send_packet -> " << ErrorToString(result) << '\n';
            if (result != AVERROR(EAGAIN)) {
                obs.stopReason = "avcodec_send_packet failed: " + ErrorToString(result);
                break;
            }
        } else if (logPacket) {
            oss << "  avcodec_send_packet -> success\n";
        }

        int framesFromPacket = 0;
        while (true) {
            result = avcodec_receive_frame(target.codecContext, target.frame);
            if (result == AVERROR(EAGAIN)) {
                if (framesFromPacket == 0) {
                    obs.packetThenEagain = true;
                }
                if (logPacket) {
                    oss << "  avcodec_receive_frame -> EAGAIN after "
                        << framesFromPacket << " frame(s)\n";
                }
                break;
            }
            if (result == AVERROR_EOF) {
                stopReading = true;
                obs.stopReason = "avcodec_receive_frame returned EOF in read loop";
                if (logPacket) {
                    oss << "  avcodec_receive_frame -> EOF\n";
                }
                break;
            }
            if (result < 0) {
                stopReading = true;
                obs.stopReason = "avcodec_receive_frame failed: " + ErrorToString(result);
                oss << "  avcodec_receive_frame -> " << ErrorToString(result) << '\n';
                break;
            }

            if (obs.packetsSinceLastFrame > 1) {
                obs.multiplePacketsThenFrame = true;
            }
            obs.packetsSinceLastFrame = 0;
            framesFromPacket++;
            obs.frameCount++;

            std::string writeError;
            const int bytes = writer.WriteFrame(target.frame, &writeError);
            if (bytes < 0) {
                oss << "  write decoded frame failed: " << writeError << '\n';
                stopReading = true;
                obs.stopReason = "writer failed: " + writeError;
                av_frame_unref(target.frame);
                break;
            }
            obs.bytesWritten += bytes;

            if (logPacket) {
                oss << "  avcodec_receive_frame -> frame#" << obs.frameCount
                    << " " << FrameDetails(target.frame, mediaType)
                    << " written=" << bytes << " bytes\n";
            }

            av_frame_unref(target.frame);
            if (obs.frameCount >= maxFrames) {
                stopReading = true;
                obs.stopReason = "hit max frame limit";
                break;
            }
        }

        if (framesFromPacket > 1) {
            obs.packetThenMultipleFrames = true;
        }
    }

    oss << "\n[flush] avcodec_send_packet(codecContext, nullptr)\n";
    result = avcodec_send_packet(target.codecContext, nullptr);
    obs.flushSent = result >= 0;
    oss << "  send null -> " << (result >= 0 ? "success" : ErrorToString(result)) << '\n';
    while (result >= 0) {
        result = avcodec_receive_frame(target.codecContext, target.frame);
        if (result == AVERROR_EOF) {
            oss << "  receive during flush -> EOF\n";
            break;
        }
        if (result == AVERROR(EAGAIN)) {
            oss << "  receive during flush -> EAGAIN\n";
            break;
        }
        if (result < 0) {
            oss << "  receive during flush -> " << ErrorToString(result) << '\n';
            break;
        }

        obs.flushProducedFrame = true;
        obs.flushFrameCount++;
        obs.frameCount++;
        std::string writeError;
        const int bytes = writer.WriteFrame(target.frame, &writeError);
        if (bytes < 0) {
            oss << "  flush frame write failed: " << writeError << '\n';
            obs.stopReason = "flush writer failed: " + writeError;
            av_frame_unref(target.frame);
            break;
        }
        obs.bytesWritten += bytes;
        oss << "  receive during flush -> frame#" << obs.frameCount
            << " " << FrameDetails(target.frame, mediaType)
            << " written=" << bytes << " bytes\n";
        av_frame_unref(target.frame);
        if (obs.frameCount >= maxFrames) {
            oss << "  stop flush after max frame limit\n";
            obs.stopReason = "hit max frame limit during flush";
            break;
        }
    }

    oss << "\nObservation Summary\n" << ObservationText(obs) << '\n';
    return oss.str();
}

std::string DecodePacketGuide(const char *path, const char *outputDir) {
    std::ostringstream oss;
    oss << "Decode Packet Guide Result\n\n";
    AppendCoreApiModel(oss);
    AppendExperimentScope(oss);
    oss << "input: " << SafeString(path) << '\n';
    oss << "output_dir: " << SafeString(outputDir) << "\n\n";

    oss << DecodeStreamToFile<tutorial_utils::AudioPcmWriter>(
        path,
        outputDir,
        AVMEDIA_TYPE_AUDIO,
        "decode_packet_audio_s16le.pcm",
        kMaxAudioFrames
    );
    oss << DecodeStreamToFile<tutorial_utils::VideoYuvWriter>(
        path,
        outputDir,
        AVMEDIA_TYPE_VIDEO,
        "decode_packet_video_yuv420p.yuv",
        kMaxVideoFrames
    );

    const std::string text = oss.str();
    LogLongTextByChunk("decode packet result", text);
    return text;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_decodePacketGuide(
    JNIEnv *env,
    jobject /* obj */,
    jlong /* nativeHandle */,
    jstring mediaPath,
    jstring outputDir
) {
    const char *pathChars = env->GetStringUTFChars(mediaPath, nullptr);
    const char *dirChars = env->GetStringUTFChars(outputDir, nullptr);
    NATIVE_LOGI_TAG(kTag, "decodePacketGuide path=%s outputDir=%s", pathChars, dirChars);
    const std::string result = DecodePacketGuide(pathChars, dirChars);
    env->ReleaseStringUTFChars(outputDir, dirChars);
    env->ReleaseStringUTFChars(mediaPath, pathChars);
    return env->NewStringUTF(result.c_str());
}
