#include "PacketReader.h"

#include <utility>

#include "NativeLog.h"

extern "C" {
#include "libavutil/avutil.h"
#include "libavutil/samplefmt.h"
}

namespace {

constexpr const char *kTag = "PacketReader";

std::string AvErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_make_error_string(buffer, sizeof(buffer), errorCode);
    return buffer;
}

const char *SafeCString(const char *value, const char *fallback = "-") {
    return value != nullptr ? value : fallback;
}

}  // namespace

struct PacketReader::Impl {
    explicit Impl(std::string sourcePath) : inputPath(std::move(sourcePath)) {}

    ~Impl() {
        Close();
    }

    std::string Open() {
        Close();

        int openResult = avformat_open_input(&formatContext, inputPath.c_str(), nullptr, nullptr);
        if (openResult < 0) {
            return std::string("Failed to open the local media file: ") + AvErrorToString(openResult);
        }

        openResult = avformat_find_stream_info(formatContext, nullptr);
        if (openResult < 0) {
            return std::string("Failed to read media stream info: ") + AvErrorToString(openResult);
        }

        audioStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (audioStreamIndex < 0) {
            return "The selected file does not contain a decodable audio stream.";
        }

        AVStream *audioStream = formatContext->streams[audioStreamIndex];
        if (audioStream == nullptr || audioStream->codecpar == nullptr) {
            return "Failed to access the selected audio stream parameters.";
        }

        codecParameters = audioStream->codecpar;
        const AVCodecDescriptor *descriptor = avcodec_descriptor_get(codecParameters->codec_id);
        streamInfo.containerName = SafeCString(formatContext->iformat != nullptr ? formatContext->iformat->name : nullptr);
        streamInfo.codecName = SafeCString(descriptor != nullptr ? descriptor->name : avcodec_get_name(codecParameters->codec_id));
        streamInfo.sourceSampleRate = codecParameters->sample_rate > 0 ? codecParameters->sample_rate : 44100;
        streamInfo.sourceChannels = codecParameters->ch_layout.nb_channels > 0 ? codecParameters->ch_layout.nb_channels : 2;
        streamInfo.sourceSampleFormat = codecParameters->format >= 0
            ? SafeCString(av_get_sample_fmt_name(static_cast<AVSampleFormat>(codecParameters->format)))
            : "-";
        streamInfo.durationSec = formatContext->duration > 0
            ? static_cast<double>(formatContext->duration) / static_cast<double>(AV_TIME_BASE)
            : -1.0;
        streamInfo.audioStreamIndex = audioStreamIndex;
        streamInfo.codecId = codecParameters->codec_id;
        streamInfo.streamTimeBase = audioStream->time_base;

        packetCount = 0;
        opened = true;
        return {};
    }

    AudioPacketReadResult ReadNextAudioPacket(AVPacket *packet, std::string *errorMessage) {
        if (packet == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "ReadNextAudioPacket received a null AVPacket output.";
            }
            return AudioPacketReadResult::Error;
        }

        av_packet_unref(packet);
        if (!opened || formatContext == nullptr || codecParameters == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "FFmpeg audio packet reader has not been opened.";
            }
            return AudioPacketReadResult::Error;
        }

        while (true) {
            const int readResult = av_read_frame(formatContext, packet);
            if (readResult == AVERROR_EOF) {
                return AudioPacketReadResult::Eof;
            }
            if (readResult < 0) {
                if (errorMessage != nullptr) {
                    *errorMessage = std::string("Failed while reading the local media file: ") + AvErrorToString(readResult);
                }
                return AudioPacketReadResult::Error;
            }

            if (packet->stream_index != audioStreamIndex) {
                av_packet_unref(packet);
                continue;
            }

            packetCount += 1;
            NATIVE_LOGD_TAG(
                kTag,
                "dispatch packet index=%lld stream=%d pts=%lld dts=%lld duration=%lld size=%d flags=0x%x",
                static_cast<long long>(packetCount - 1),
                packet->stream_index,
                static_cast<long long>(packet->pts),
                static_cast<long long>(packet->dts),
                static_cast<long long>(packet->duration),
                packet->size,
                packet->flags
            );
            return AudioPacketReadResult::Packet;
        }
    }

    void Close() {
        if (formatContext != nullptr) {
            avformat_close_input(&formatContext);
        }
        codecParameters = nullptr;
        audioStreamIndex = -1;
        packetCount = 0;
        opened = false;
        streamInfo = AudioPacketStreamInfo{};
    }

    std::string inputPath;
    AudioPacketStreamInfo streamInfo{};
    AVFormatContext *formatContext = nullptr;
    const AVCodecParameters *codecParameters = nullptr;
    int audioStreamIndex = -1;
    int64_t packetCount = 0;
    bool opened = false;
};

PacketReader::PacketReader(std::string inputPath)
    : impl_(std::make_unique<Impl>(std::move(inputPath))) {}

PacketReader::~PacketReader() = default;

std::string PacketReader::Open() {
    return impl_->Open();
}

AudioPacketReadResult PacketReader::ReadNextAudioPacket(
    AVPacket *packet,
    std::string *errorMessage
) {
    return impl_->ReadNextAudioPacket(packet, errorMessage);
}

const std::string &PacketReader::GetInputPath() const {
    return impl_->inputPath;
}

const AudioPacketStreamInfo &PacketReader::GetStreamInfo() const {
    return impl_->streamInfo;
}

const AVCodecParameters *PacketReader::GetAudioCodecParameters() const {
    return impl_->codecParameters;
}

AVRational PacketReader::GetAudioStreamTimeBase() const {
    return impl_->streamInfo.streamTimeBase;
}

int64_t PacketReader::GetPacketCount() const {
    return impl_->packetCount;
}
