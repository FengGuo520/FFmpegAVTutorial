#include "FfmpegAudioDecoder.h"

#include <sstream>
#include <utility>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
}

namespace {

constexpr const char *kTag = "FfmpegAudioDecoder";

std::string AvErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_make_error_string(buffer, sizeof(buffer), errorCode);
    return buffer;
}

const char *SafeCString(const char *value, const char *fallback = "-") {
    return value != nullptr ? value : fallback;
}

}  // namespace

struct FfmpegAudioDecoder::Impl {
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
        decoder = avcodec_find_decoder(audioStream->codecpar->codec_id);
        if (decoder == nullptr) {
            return std::string("Failed to find a decoder for the selected audio stream codec: ") +
                SafeCString(avcodec_get_name(audioStream->codecpar->codec_id), "unknown");
        }

        codecContext = avcodec_alloc_context3(decoder);
        if (codecContext == nullptr) {
            return "Failed to allocate the native audio decoder context.";
        }

        openResult = avcodec_parameters_to_context(codecContext, audioStream->codecpar);
        if (openResult < 0) {
            return std::string("Failed to copy codec parameters into the decoder context: ") + AvErrorToString(openResult);
        }

        openResult = avcodec_open2(codecContext, decoder, nullptr);
        if (openResult < 0) {
            return std::string("Failed to open the audio decoder: ") + AvErrorToString(openResult);
        }

        packet = av_packet_alloc();
        frame = av_frame_alloc();
        if (packet == nullptr || frame == nullptr) {
            return "Failed to allocate AVPacket or AVFrame for audio decode.";
        }

        streamInfo.containerName = SafeCString(formatContext->iformat != nullptr ? formatContext->iformat->name : nullptr);
        streamInfo.codecName = SafeCString(decoder->name);
        streamInfo.sourceSampleRate = codecContext->sample_rate > 0 ? codecContext->sample_rate : 44100;
        streamInfo.sourceChannels = codecContext->ch_layout.nb_channels > 0 ? codecContext->ch_layout.nb_channels : 2;
        streamInfo.sourceSampleFormat = SafeCString(av_get_sample_fmt_name(codecContext->sample_fmt));
        streamInfo.outputSampleRate = streamInfo.sourceSampleRate;
        streamInfo.outputChannels = streamInfo.sourceChannels > 1 ? 2 : 1;
        streamInfo.durationSec = formatContext->duration > 0
            ? static_cast<double>(formatContext->duration) / static_cast<double>(AV_TIME_BASE)
            : -1.0;

        av_channel_layout_default(&outputChannelLayout, streamInfo.outputChannels);
        openResult = swr_alloc_set_opts2(
            &swrContext,
            &outputChannelLayout,
            AV_SAMPLE_FMT_S16,
            streamInfo.outputSampleRate,
            &codecContext->ch_layout,
            codecContext->sample_fmt,
            streamInfo.sourceSampleRate,
            0,
            nullptr
        );
        if (openResult < 0 || swrContext == nullptr) {
            return std::string("Failed to allocate the audio resampler: ") + AvErrorToString(openResult);
        }

        openResult = swr_init(swrContext);
        if (openResult < 0) {
            return std::string("Failed to initialize the audio resampler: ") + AvErrorToString(openResult);
        }

        packetCount = 0;
        decodedFrameCount = 0;
        flushSent = false;
        opened = true;
        return {};
    }

    AudioDecoderReadResult ReadNextPcmBlock(AudioDecoderPcmBlock *block, std::string *errorMessage) {
        if (block == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "ReadNextPcmBlock received a null output block.";
            }
            return AudioDecoderReadResult::Error;
        }
        block->pcmData.clear();

        if (!opened || formatContext == nullptr || codecContext == nullptr || swrContext == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "FFmpeg audio decoder has not been opened.";
            }
            return AudioDecoderReadResult::Error;
        }

        while (true) {
            const int receiveResult = avcodec_receive_frame(codecContext, frame);
            if (receiveResult == 0) {
                return ConvertFrameToBlock(block, errorMessage);
            }
            if (receiveResult == AVERROR_EOF) {
                return AudioDecoderReadResult::Eof;
            }
            if (receiveResult != AVERROR(EAGAIN)) {
                if (errorMessage != nullptr) {
                    *errorMessage = std::string("Failed to receive decoded audio frame: ") + AvErrorToString(receiveResult);
                }
                return AudioDecoderReadResult::Error;
            }

            if (flushSent) {
                const int flushReceiveResult = avcodec_send_packet(codecContext, nullptr);
                if (flushReceiveResult == AVERROR_EOF) {
                    return AudioDecoderReadResult::Eof;
                }
                if (flushReceiveResult < 0 && flushReceiveResult != AVERROR(EAGAIN)) {
                    if (errorMessage != nullptr) {
                        *errorMessage = std::string("Failed to flush the audio decoder: ") + AvErrorToString(flushReceiveResult);
                    }
                    return AudioDecoderReadResult::Error;
                }
            }

            bool packetLoaded = false;
            while (!packetLoaded) {
                const int readResult = av_read_frame(formatContext, packet);
                if (readResult == AVERROR_EOF) {
                    flushSent = true;
                    const int flushResult = avcodec_send_packet(codecContext, nullptr);
                    if (flushResult < 0 && flushResult != AVERROR_EOF && flushResult != AVERROR(EAGAIN)) {
                        if (errorMessage != nullptr) {
                            *errorMessage = std::string("Failed to flush the audio decoder: ") + AvErrorToString(flushResult);
                        }
                        return AudioDecoderReadResult::Error;
                    }
                    break;
                }
                if (readResult < 0) {
                    if (errorMessage != nullptr) {
                        *errorMessage = std::string("Failed while reading the local media file: ") + AvErrorToString(readResult);
                    }
                    return AudioDecoderReadResult::Error;
                }
                if (packet->stream_index != audioStreamIndex) {
                    av_packet_unref(packet);
                    continue;
                }

                packetCount += 1;
                NATIVE_LOGD_TAG(
                    kTag,
                    "input packet index=%lld stream=%d pts=%lld dts=%lld duration=%lld size=%d flags=0x%x",
                    static_cast<long long>(packetCount - 1),
                    packet->stream_index,
                    static_cast<long long>(packet->pts),
                    static_cast<long long>(packet->dts),
                    static_cast<long long>(packet->duration),
                    packet->size,
                    packet->flags
                );
                packetLoaded = true;
            }

            if (flushSent && !packetLoaded) {
                continue;
            }

            const int sendResult = avcodec_send_packet(codecContext, packet);
            av_packet_unref(packet);
            if (sendResult < 0 && sendResult != AVERROR(EAGAIN)) {
                if (errorMessage != nullptr) {
                    *errorMessage = std::string("Failed to send packet into the audio decoder: ") + AvErrorToString(sendResult);
                }
                return AudioDecoderReadResult::Error;
            }
        }
    }

    AudioDecoderReadResult ConvertFrameToBlock(AudioDecoderPcmBlock *block, std::string *errorMessage) {
        const int delaySamples = swr_get_delay(swrContext, streamInfo.sourceSampleRate);
        int targetSamples = av_rescale_rnd(
            static_cast<int64_t>(delaySamples) + frame->nb_samples,
            streamInfo.outputSampleRate,
            streamInfo.sourceSampleRate,
            AV_ROUND_UP
        );
        if (targetSamples <= 0) {
            targetSamples = frame->nb_samples;
        }

        const int targetBufferSize = av_samples_get_buffer_size(
            nullptr,
            streamInfo.outputChannels,
            targetSamples,
            AV_SAMPLE_FMT_S16,
            1
        );
        if (targetBufferSize <= 0) {
            av_frame_unref(frame);
            if (errorMessage != nullptr) {
                *errorMessage = "Unable to allocate a PCM buffer for the resampled audio.";
            }
            return AudioDecoderReadResult::Error;
        }

        block->pcmData.resize(static_cast<size_t>(targetBufferSize));
        uint8_t *outputData[1] = {block->pcmData.data()};
        const int convertedSamples = swr_convert(
            swrContext,
            outputData,
            targetSamples,
            (const uint8_t **)frame->extended_data,
            frame->nb_samples
        );
        if (convertedSamples < 0) {
            av_frame_unref(frame);
            if (errorMessage != nullptr) {
                *errorMessage = std::string("Audio resample failed: ") + AvErrorToString(convertedSamples);
            }
            return AudioDecoderReadResult::Error;
        }

        const int pcmSize = av_samples_get_buffer_size(
            nullptr,
            streamInfo.outputChannels,
            convertedSamples,
            AV_SAMPLE_FMT_S16,
            1
        );
        if (pcmSize <= 0) {
            av_frame_unref(frame);
            if (errorMessage != nullptr) {
                *errorMessage = "Audio resample produced an invalid PCM buffer size.";
            }
            return AudioDecoderReadResult::Error;
        }

        block->pcmData.resize(static_cast<size_t>(pcmSize));
        block->frameIndex = decodedFrameCount;
        block->framePts = frame->pts;
        block->frameNbSamples = frame->nb_samples;
        block->frameFormat = SafeCString(av_get_sample_fmt_name(static_cast<AVSampleFormat>(frame->format)));
        block->frameSampleRate = frame->sample_rate;
        block->frameChannels = frame->ch_layout.nb_channels;

        decodedFrameCount += 1;
        NATIVE_LOGD_TAG(
            kTag,
            "decoded frame index=%lld pts=%lld nb_samples=%d format=%s sample_rate=%d channels=%d",
            static_cast<long long>(block->frameIndex),
            static_cast<long long>(block->framePts),
            block->frameNbSamples,
            block->frameFormat.c_str(),
            block->frameSampleRate,
            block->frameChannels
        );

        av_frame_unref(frame);
        return AudioDecoderReadResult::Block;
    }

    void Close() {
        if (packet != nullptr) {
            av_packet_free(&packet);
        }
        if (frame != nullptr) {
            av_frame_free(&frame);
        }
        if (swrContext != nullptr) {
            swr_free(&swrContext);
        }
        av_channel_layout_uninit(&outputChannelLayout);
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        if (formatContext != nullptr) {
            avformat_close_input(&formatContext);
        }
        decoder = nullptr;
        audioStreamIndex = -1;
        flushSent = false;
        opened = false;
        streamInfo = AudioDecoderStreamInfo{};
    }

    std::string inputPath;
    AudioDecoderStreamInfo streamInfo{};
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    const AVCodec *decoder = nullptr;
    SwrContext *swrContext = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    AVChannelLayout outputChannelLayout{};
    int audioStreamIndex = -1;
    bool flushSent = false;
    bool opened = false;
    int64_t packetCount = 0;
    int64_t decodedFrameCount = 0;
};

FfmpegAudioDecoder::FfmpegAudioDecoder(std::string inputPath)
    : impl_(std::make_unique<Impl>(std::move(inputPath))) {}

FfmpegAudioDecoder::~FfmpegAudioDecoder() = default;

std::string FfmpegAudioDecoder::Open() {
    return impl_->Open();
}

AudioDecoderReadResult FfmpegAudioDecoder::ReadNextPcmBlock(
    AudioDecoderPcmBlock *block,
    std::string *errorMessage
) {
    return impl_->ReadNextPcmBlock(block, errorMessage);
}

const std::string &FfmpegAudioDecoder::GetInputPath() const {
    return impl_->inputPath;
}

const AudioDecoderStreamInfo &FfmpegAudioDecoder::GetStreamInfo() const {
    return impl_->streamInfo;
}

int64_t FfmpegAudioDecoder::GetPacketCount() const {
    return impl_->packetCount;
}

int64_t FfmpegAudioDecoder::GetDecodedFrameCount() const {
    return impl_->decodedFrameCount;
}
