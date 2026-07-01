#include <jni.h>

#include <iomanip>
#include <sstream>
#include <string>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_desc.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/pixdesc.h"
#include "libavutil/rational.h"
#include "libavutil/samplefmt.h"
}

namespace {

std::string ErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

std::string FormatDurationSeconds(int64_t timestamp, AVRational timeBase) {
    if (timestamp == AV_NOPTS_VALUE || timestamp < 0 || timeBase.num == 0 || timeBase.den == 0) {
        return "unknown";
    }
    const double seconds = timestamp * av_q2d(timeBase);
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(3) << seconds << " s";
    return oss.str();
}

std::string FormatDurationUs(int64_t durationUs) {
    if (durationUs == AV_NOPTS_VALUE || durationUs < 0) {
        return "unknown";
    }
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(3)
        << static_cast<double>(durationUs) / AV_TIME_BASE << " s";
    return oss.str();
}

std::string FormatBitrate(int64_t bitRate) {
    if (bitRate <= 0) {
        return "unknown";
    }
    std::ostringstream oss;
    if (bitRate >= 1000000) {
        oss << std::fixed << std::setprecision(2)
            << static_cast<double>(bitRate) / 1000000.0 << " Mbps";
    } else if (bitRate >= 1000) {
        oss << std::fixed << std::setprecision(2)
            << static_cast<double>(bitRate) / 1000.0 << " kbps";
    } else {
        oss << bitRate << " bps";
    }
    oss << " (" << bitRate << " bps)";
    return oss.str();
}

std::string FormatRational(AVRational value) {
    std::ostringstream oss;
    oss << value.num << "/" << value.den;
    if (value.num != 0 && value.den != 0) {
        oss << " = " << std::fixed << std::setprecision(3) << av_q2d(value);
    }
    return oss.str();
}

const char *MediaTypeName(AVMediaType mediaType) {
    const char *name = av_get_media_type_string(mediaType);
    return name != nullptr ? name : "unknown";
}

std::string CodecName(const AVCodecParameters *parameters) {
    if (parameters == nullptr) {
        return "unknown";
    }
    const AVCodecDescriptor *descriptor = avcodec_descriptor_get(parameters->codec_id);
    std::ostringstream oss;
    oss << avcodec_get_name(parameters->codec_id);
    if (descriptor != nullptr && descriptor->long_name != nullptr) {
        oss << " - " << descriptor->long_name;
    }
    return oss.str();
}

void LogLongTextByChunk(const char *tag, const std::string &title, const std::string &text, size_t chunkSize = 1800) {
    NATIVE_LOGI_TAG(tag, "%s begin", title.c_str());
    if (text.empty()) {
        NATIVE_LOGI_TAG(tag, "%s <empty>", title.c_str());
        NATIVE_LOGI_TAG(tag, "%s end", title.c_str());
        return;
    }

    size_t offset = 0;
    int chunkIndex = 0;
    while (offset < text.size()) {
        size_t length = std::min(chunkSize, text.size() - offset);
        size_t breakPos = text.rfind('\n', offset + length);
        if (breakPos != std::string::npos && breakPos >= offset && breakPos < offset + length) {
            length = breakPos - offset + 1;
        }
        const std::string chunk = text.substr(offset, length);
        NATIVE_LOGI_TAG(tag, "%s chunk#%d:\n%s", title.c_str(), chunkIndex, chunk.c_str());
        offset += length;
        chunkIndex++;
    }
    NATIVE_LOGI_TAG(tag, "%s end totalChunks=%d", title.c_str(), chunkIndex);
}

void AppendMetadata(std::ostringstream &oss, const AVDictionary *metadata, const char *indent) {
    const AVDictionaryEntry *entry = nullptr;
    int count = 0;
    while ((entry = av_dict_get(metadata, "", entry, AV_DICT_IGNORE_SUFFIX)) != nullptr) {
        oss << indent << entry->key << ": " << entry->value << '\n';
        count++;
    }
    if (count == 0) {
        oss << indent << "none\n";
    }
}

void AppendFormatInfo(std::ostringstream &oss, AVFormatContext *formatContext) {
    const AVInputFormat *inputFormat = formatContext->iformat;
    int64_t ioSize = -1;
    if (formatContext->pb != nullptr) {
        ioSize = avio_size(formatContext->pb);
    }

    oss << "Container\n";
    oss << "format_name: " << (inputFormat && inputFormat->name ? inputFormat->name : "unknown") << '\n';
    oss << "format_long_name: " << (inputFormat && inputFormat->long_name ? inputFormat->long_name : "unknown") << '\n';
    oss << "url: " << (formatContext->url ? formatContext->url : "unknown") << '\n';
    oss << "duration: " << FormatDurationUs(formatContext->duration) << '\n';
    oss << "start_time: " << FormatDurationUs(formatContext->start_time) << '\n';
    oss << "bit_rate: " << FormatBitrate(formatContext->bit_rate) << '\n';
    oss << "stream_count: " << formatContext->nb_streams << '\n';
    if (ioSize >= 0) {
        oss << "file_size_bytes: " << ioSize << '\n';
    } else {
        oss << "file_size_bytes: unknown\n";
    }
    oss << "metadata:\n";
    AppendMetadata(oss, formatContext->metadata, "  ");
    oss << '\n';
}

void AppendStreamInfo(std::ostringstream &oss, AVFormatContext *formatContext) {
    oss << "Streams\n";
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        AVCodecParameters *parameters = stream->codecpar;
        oss << "stream #" << i << '\n';
        oss << "  media_type: " << MediaTypeName(parameters->codec_type) << '\n';
        oss << "  codec: " << CodecName(parameters) << '\n';
        oss << "  codec_type_id: " << parameters->codec_type << '\n';
        oss << "  codec_id: " << parameters->codec_id << '\n';
        oss << "  codec_tag: 0x" << std::hex << parameters->codec_tag << std::dec << '\n';
        oss << "  time_base: " << FormatRational(stream->time_base) << '\n';
        oss << "  avg_frame_rate: " << FormatRational(stream->avg_frame_rate) << '\n';
        oss << "  real_frame_rate: " << FormatRational(stream->r_frame_rate) << '\n';
        oss << "  start_time: " << FormatDurationSeconds(stream->start_time, stream->time_base) << '\n';
        oss << "  duration: " << FormatDurationSeconds(stream->duration, stream->time_base) << '\n';
        oss << "  bit_rate: " << FormatBitrate(parameters->bit_rate) << '\n';
        oss << "  frames: " << stream->nb_frames << '\n';

        if (parameters->codec_type == AVMEDIA_TYPE_VIDEO) {
            const char *pixelFormatName = parameters->format >= 0
                ? av_get_pix_fmt_name(static_cast<AVPixelFormat>(parameters->format))
                : nullptr;
            oss << "  video.width: " << parameters->width << '\n';
            oss << "  video.height: " << parameters->height << '\n';
            oss << "  video.pixel_format: " << (pixelFormatName ? pixelFormatName : "unknown") << '\n';
            oss << "  video.sample_aspect_ratio: " << FormatRational(parameters->sample_aspect_ratio) << '\n';
        } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO) {
            char layout[256] = {0};
            const char *sampleFormatName = parameters->format >= 0
                ? av_get_sample_fmt_name(static_cast<AVSampleFormat>(parameters->format))
                : nullptr;
            av_channel_layout_describe(&parameters->ch_layout, layout, sizeof(layout));
            oss << "  audio.sample_rate: " << parameters->sample_rate << " Hz\n";
            oss << "  audio.channels: " << parameters->ch_layout.nb_channels << '\n';
            oss << "  audio.channel_layout: " << layout << '\n';
            oss << "  audio.sample_format: " << (sampleFormatName ? sampleFormatName : "unknown") << '\n';
            oss << "  audio.frame_size: " << parameters->frame_size << '\n';
        } else if (parameters->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            oss << "  subtitle.width: " << parameters->width << '\n';
            oss << "  subtitle.height: " << parameters->height << '\n';
        }

        oss << "  metadata:\n";
        AppendMetadata(oss, stream->metadata, "    ");
        oss << '\n';
    }
}

void AppendPacketSamples(std::ostringstream &oss, AVFormatContext *formatContext) {
    oss << "Demuxed Packet Samples\n";
    oss << "api: av_read_frame\n";
    oss << "note: each row is one compressed AVPacket read from the demuxer.\n";

    AVPacket *packet = av_packet_alloc();
    if (packet == nullptr) {
        oss << "error: av_packet_alloc failed\n";
        return;
    }

    int count = 0;
    while (count < 12) {
        int result = av_read_frame(formatContext, packet);
        if (result < 0) {
            oss << "read_end: " << ErrorToString(result) << '\n';
            break;
        }

        AVStream *stream = packet->stream_index >= 0 && packet->stream_index < static_cast<int>(formatContext->nb_streams)
            ? formatContext->streams[packet->stream_index]
            : nullptr;
        AVRational timeBase = stream != nullptr ? stream->time_base : AVRational{1, AV_TIME_BASE};
        const char *typeName = stream != nullptr
            ? MediaTypeName(stream->codecpar->codec_type)
            : "unknown";

        std::ostringstream line;
        line << "packet #" << count
             << " stream=" << packet->stream_index
             << " type=" << typeName
             << " size=" << packet->size
             << " pts=" << packet->pts
             << " (" << FormatDurationSeconds(packet->pts, timeBase) << ")"
             << " dts=" << packet->dts
             << " (" << FormatDurationSeconds(packet->dts, timeBase) << ")"
             << " duration=" << packet->duration
             << " (" << FormatDurationSeconds(packet->duration, timeBase) << ")"
             << " flags=0x" << std::hex << packet->flags << std::dec;
        if ((packet->flags & AV_PKT_FLAG_KEY) != 0) {
            line << " key";
        }
        oss << line.str() << '\n';

        av_packet_unref(packet);
        count++;
    }

    av_packet_free(&packet);
    oss << '\n';
}

std::string ProbeMediaFile(const char *path) {
    std::ostringstream oss;
    oss << "Media Probe Result\n\n";
    oss << "Demux Flow\n";
    oss << "1. avformat_open_input(path)\n";
    oss << "2. avformat_find_stream_info(context)\n";
    oss << "3. read AVFormatContext / AVStream / AVCodecParameters\n";
    oss << "4. av_read_frame(context, packet) samples compressed packets\n\n";

    AVFormatContext *formatContext = nullptr;
    int result = avformat_open_input(&formatContext, path, nullptr, nullptr);
    if (result < 0) {
        return "ERROR: avformat_open_input failed: " + ErrorToString(result);
    }
    NATIVE_LOGI_TAG("MediaProbeDemo", "avformat_open_input success path=%s", path);

    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) {
        std::string error = ErrorToString(result);
        avformat_close_input(&formatContext);
        return "ERROR: avformat_find_stream_info failed: " + error;
    }
    NATIVE_LOGI_TAG(
        "MediaProbeDemo",
        "avformat_find_stream_info success streams=%u duration=%lld "
        "duration_time_base=%d/%d duration_seconds=%.3f bit_rate=%lld",
        formatContext->nb_streams,
        static_cast<long long>(formatContext->duration),
        AV_TIME_BASE_Q.num,
        AV_TIME_BASE_Q.den,
        formatContext->duration == AV_NOPTS_VALUE
            ? -1.0
            : static_cast<double>(formatContext->duration) * av_q2d(AV_TIME_BASE_Q),
        static_cast<long long>(formatContext->bit_rate)
    );

    AppendFormatInfo(oss, formatContext);
    AppendStreamInfo(oss, formatContext);
    AppendPacketSamples(oss, formatContext);

    std::string resultText = oss.str();
    LogLongTextByChunk("MediaProbeDemo", "probe result", resultText);
    avformat_close_input(&formatContext);
    return resultText;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_probeMediaFile(
    JNIEnv *env,
    jobject /* obj */,
    jlong /* nativeHandle */,
    jstring mediaPath
) {
    const char *pathChars = env->GetStringUTFChars(mediaPath, nullptr);
    NATIVE_LOGI_TAG("MediaProbeDemo", "probeMediaFile path=%s", pathChars);
    std::string result = ProbeMediaFile(pathChars);
    env->ReleaseStringUTFChars(mediaPath, pathChars);
    return env->NewStringUTF(result.c_str());
}
