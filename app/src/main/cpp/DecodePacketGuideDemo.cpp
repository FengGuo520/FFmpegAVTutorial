#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_desc.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/imgutils.h"
#include "libavutil/pixdesc.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
#include "libswscale/swscale.h"
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
    int packetsSinceLastFrame = 0;
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
    oss << "stream_index: " << target.streamIndex << '\n';
    oss << "codec: " << CodecLabel(target.codecContext->codec_id) << '\n';
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
    return oss.str();
}

class AudioPcmWriter {
public:
    std::string Open(AVCodecContext *codecContext, const std::string &path) {
        if (codecContext->ch_layout.nb_channels <= 0) {
            av_channel_layout_default(&outputLayout_, 2);
        } else {
            int result = av_channel_layout_copy(&outputLayout_, &codecContext->ch_layout);
            if (result < 0) {
                return "av_channel_layout_copy failed: " + ErrorToString(result);
            }
        }
        outputSampleRate_ = codecContext->sample_rate > 0 ? codecContext->sample_rate : 44100;
        outputChannels_ = outputLayout_.nb_channels;

        int result = swr_alloc_set_opts2(
            &swrContext_,
            &outputLayout_,
            AV_SAMPLE_FMT_S16,
            outputSampleRate_,
            &codecContext->ch_layout,
            codecContext->sample_fmt,
            codecContext->sample_rate,
            0,
            nullptr
        );
        if (result < 0 || swrContext_ == nullptr) {
            return "swr_alloc_set_opts2 failed: " + ErrorToString(result);
        }

        result = swr_init(swrContext_);
        if (result < 0) {
            return "swr_init failed: " + ErrorToString(result);
        }

        file_.open(path, std::ios::binary);
        if (!file_.is_open()) {
            return "failed to open output pcm file: " + path;
        }
        path_ = path;
        return {};
    }

    ~AudioPcmWriter() {
        if (swrContext_ != nullptr) {
            swr_free(&swrContext_);
        }
        av_channel_layout_uninit(&outputLayout_);
    }

    int WriteFrame(const AVFrame *frame, std::string *error) {
        const int64_t delay = swr_get_delay(swrContext_, frame->sample_rate);
        const int outSamples = static_cast<int>(av_rescale_rnd(
            delay + frame->nb_samples,
            outputSampleRate_,
            frame->sample_rate,
            AV_ROUND_UP
        ));
        const int bufferSize = av_samples_get_buffer_size(
            nullptr,
            outputChannels_,
            outSamples,
            AV_SAMPLE_FMT_S16,
            1
        );
        if (bufferSize <= 0) {
            if (error != nullptr) {
                *error = "av_samples_get_buffer_size failed: " + ErrorToString(bufferSize);
            }
            return -1;
        }

        std::vector<uint8_t> buffer(static_cast<size_t>(bufferSize));
        uint8_t *outData = buffer.data();
        const int converted = swr_convert(
            swrContext_,
            &outData,
            outSamples,
            const_cast<const uint8_t **>(frame->extended_data),
            frame->nb_samples
        );
        if (converted < 0) {
            if (error != nullptr) {
                *error = "swr_convert failed: " + ErrorToString(converted);
            }
            return -1;
        }

        const int bytes = converted * outputChannels_ *
            av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        file_.write(reinterpret_cast<const char *>(buffer.data()), bytes);
        return bytes;
    }

    std::string Summary() const {
        std::ostringstream oss;
        oss << "output_file: " << path_ << '\n';
        oss << "output_format: s16le packed PCM\n";
        oss << "output_sample_rate: " << outputSampleRate_ << '\n';
        oss << "output_channels: " << outputChannels_ << '\n';
        oss << "play_hint: ffplay -f s16le -ar " << outputSampleRate_
            << " -ac " << outputChannels_ << " " << path_ << '\n';
        return oss.str();
    }

private:
    SwrContext *swrContext_ = nullptr;
    AVChannelLayout outputLayout_{};
    int outputSampleRate_ = 0;
    int outputChannels_ = 0;
    std::ofstream file_;
    std::string path_;
};

class VideoYuvWriter {
public:
    std::string Open(AVCodecContext *codecContext, const std::string &path) {
        width_ = codecContext->width;
        height_ = codecContext->height;
        sourceFormat_ = codecContext->pix_fmt;
        swsContext_ = sws_getContext(
            width_,
            height_,
            sourceFormat_,
            width_,
            height_,
            AV_PIX_FMT_YUV420P,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr
        );
        if (swsContext_ == nullptr) {
            return "sws_getContext failed";
        }

        const int imageSize = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width_, height_, 1);
        if (imageSize <= 0) {
            return "av_image_get_buffer_size failed: " + ErrorToString(imageSize);
        }
        imageBuffer_.resize(static_cast<size_t>(imageSize));

        int result = av_image_alloc(dstData_, dstLinesize_, width_, height_, AV_PIX_FMT_YUV420P, 1);
        if (result < 0) {
            return "av_image_alloc failed: " + ErrorToString(result);
        }

        file_.open(path, std::ios::binary);
        if (!file_.is_open()) {
            return "failed to open output yuv file: " + path;
        }
        path_ = path;
        return {};
    }

    ~VideoYuvWriter() {
        if (dstData_[0] != nullptr) {
            av_freep(&dstData_[0]);
        }
        if (swsContext_ != nullptr) {
            sws_freeContext(swsContext_);
        }
    }

    int WriteFrame(const AVFrame *frame, std::string *error) {
        const int scaledHeight = sws_scale(
            swsContext_,
            frame->data,
            frame->linesize,
            0,
            height_,
            dstData_,
            dstLinesize_
        );
        if (scaledHeight <= 0) {
            if (error != nullptr) {
                *error = "sws_scale failed";
            }
            return -1;
        }

        const int copied = av_image_copy_to_buffer(
            imageBuffer_.data(),
            static_cast<int>(imageBuffer_.size()),
            dstData_,
            dstLinesize_,
            AV_PIX_FMT_YUV420P,
            width_,
            height_,
            1
        );
        if (copied < 0) {
            if (error != nullptr) {
                *error = "av_image_copy_to_buffer failed: " + ErrorToString(copied);
            }
            return -1;
        }

        file_.write(reinterpret_cast<const char *>(imageBuffer_.data()), copied);
        return copied;
    }

    std::string Summary() const {
        std::ostringstream oss;
        oss << "output_file: " << path_ << '\n';
        oss << "output_format: yuv420p raw video\n";
        oss << "output_width: " << width_ << '\n';
        oss << "output_height: " << height_ << '\n';
        oss << "source_pixel_format: " << SafeString(av_get_pix_fmt_name(sourceFormat_)) << '\n';
        oss << "play_hint: ffplay -f rawvideo -pixel_format yuv420p -video_size "
            << width_ << "x" << height_ << " " << path_ << '\n';
        return oss.str();
    }

private:
    SwsContext *swsContext_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    AVPixelFormat sourceFormat_ = AV_PIX_FMT_NONE;
    uint8_t *dstData_[4] = {nullptr, nullptr, nullptr, nullptr};
    int dstLinesize_[4] = {0, 0, 0, 0};
    std::vector<uint8_t> imageBuffer_;
    std::ofstream file_;
    std::string path_;
};

template <typename Writer>
std::string DecodeStreamToFile(
    const char *path,
    const char *outputDir,
    AVMediaType mediaType,
    const std::string &outputName,
    int maxFrames
) {
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
    oss << writer.Summary() << '\n';

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
                if (logPacket) {
                    oss << "  avcodec_receive_frame -> EOF\n";
                }
                break;
            }
            if (result < 0) {
                stopReading = true;
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
                av_frame_unref(target.frame);
                break;
            }
            obs.bytesWritten += bytes;

            if (logPacket) {
                oss << "  avcodec_receive_frame -> frame#" << obs.frameCount;
                if (mediaType == AVMEDIA_TYPE_AUDIO) {
                    oss << " nb_samples=" << target.frame->nb_samples
                        << " sample_fmt="
                        << SafeString(av_get_sample_fmt_name(static_cast<AVSampleFormat>(target.frame->format)));
                } else {
                    oss << " width=" << target.frame->width
                        << " height=" << target.frame->height
                        << " pix_fmt="
                        << SafeString(av_get_pix_fmt_name(static_cast<AVPixelFormat>(target.frame->format)));
                }
                oss << " written=" << bytes << " bytes\n";
            }

            av_frame_unref(target.frame);
            if (obs.frameCount >= maxFrames) {
                stopReading = true;
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
        obs.frameCount++;
        std::string writeError;
        const int bytes = writer.WriteFrame(target.frame, &writeError);
        if (bytes < 0) {
            oss << "  flush frame write failed: " << writeError << '\n';
            av_frame_unref(target.frame);
            break;
        }
        obs.bytesWritten += bytes;
        oss << "  receive during flush -> frame#" << obs.frameCount
            << " written=" << bytes << " bytes\n";
        av_frame_unref(target.frame);
        if (obs.frameCount >= maxFrames) {
            oss << "  stop flush after max frame limit\n";
            break;
        }
    }

    oss << "\nObservation Summary\n" << ObservationText(obs) << '\n';
    return oss.str();
}

std::string DecodePacketGuide(const char *path, const char *outputDir) {
    std::ostringstream oss;
    oss << "Decode Packet Guide Result\n\n";
    oss << "Core API model\n";
    oss << "1. av_read_frame reads compressed AVPacket from demuxer.\n";
    oss << "2. avcodec_send_packet queues compressed data into decoder.\n";
    oss << "3. avcodec_receive_frame pulls decoded AVFrame out.\n";
    oss << "4. AVERROR(EAGAIN) means this side cannot progress right now.\n";
    oss << "5. avcodec_send_packet(ctx, nullptr) flushes delayed frames at EOF.\n\n";
    oss << "input: " << SafeString(path) << '\n';
    oss << "output_dir: " << SafeString(outputDir) << "\n\n";

    oss << DecodeStreamToFile<AudioPcmWriter>(
        path,
        outputDir,
        AVMEDIA_TYPE_AUDIO,
        "decode_packet_audio_s16le.pcm",
        kMaxAudioFrames
    );
    oss << DecodeStreamToFile<VideoYuvWriter>(
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
