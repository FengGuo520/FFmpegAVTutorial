#include "FfmpegAudioDecoder.h"

#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
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
    ~Impl() {
        Close();
    }

    std::string Open(
        const AudioPacketStreamInfo &packetStreamInfo,
        const AVCodecParameters *codecParameters,
        AVRational packetTimeBase
    ) {
        Close();

        if (codecParameters == nullptr) {
            return "The audio packet reader did not expose valid codec parameters.";
        }

        decoder = avcodec_find_decoder(codecParameters->codec_id);
        if (decoder == nullptr) {
            return std::string("Failed to find a decoder for the selected audio stream codec: ") +
                SafeCString(avcodec_get_name(codecParameters->codec_id), "unknown");
        }

        codecContext = avcodec_alloc_context3(decoder);
        if (codecContext == nullptr) {
            return "Failed to allocate the native audio decoder context.";
        }

        int openResult = avcodec_parameters_to_context(codecContext, codecParameters);
        if (openResult < 0) {
            return std::string("Failed to copy codec parameters into the decoder context: ") + AvErrorToString(openResult);
        }
        codecContext->pkt_timebase = packetTimeBase;

        openResult = avcodec_open2(codecContext, decoder, nullptr);
        if (openResult < 0) {
            return std::string("Failed to open the audio decoder: ") + AvErrorToString(openResult);
        }

        packet = av_packet_alloc();
        frame = av_frame_alloc();
        if (packet == nullptr || frame == nullptr) {
            return "Failed to allocate AVPacket or AVFrame for audio decode.";
        }

        streamInfo.containerName = packetStreamInfo.containerName;
        streamInfo.codecName = SafeCString(decoder->name);
        streamInfo.sourceSampleRate = codecContext->sample_rate > 0 ? codecContext->sample_rate : 44100;
        streamInfo.sourceChannels = codecContext->ch_layout.nb_channels > 0 ? codecContext->ch_layout.nb_channels : 2;
        streamInfo.sourceSampleFormat = SafeCString(av_get_sample_fmt_name(codecContext->sample_fmt));
        streamInfo.outputSampleRate = streamInfo.sourceSampleRate;
        streamInfo.outputChannels = streamInfo.sourceChannels > 1 ? 2 : 1;
        streamInfo.durationSec = packetStreamInfo.durationSec;

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

        decodedFrameCount = 0;
        flushSent = false;
        opened = true;
        return {};
    }

    AudioDecoderReadResult ReadNextPcmBlock(
        PacketQueue *packetQueue,
        AudioDecoderPcmBlock *block,
        std::string *errorMessage
    ) {
        if (block == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "ReadNextPcmBlock received a null output block.";
            }
            return AudioDecoderReadResult::Error;
        }
        block->pcmData.clear();

        if (!opened || codecContext == nullptr || swrContext == nullptr || packetQueue == nullptr) {
            if (errorMessage != nullptr) {
                *errorMessage = "FFmpeg audio decoder has not been opened with a valid packet queue.";
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

            std::string packetError;
            const PacketQueueReadResult packetReadResult =
                packetQueue->Read(packet, &packetError);
            if (packetReadResult == PacketQueueReadResult::Eof) {
                if (!flushSent) {
                    flushSent = true;
                    const int flushResult = avcodec_send_packet(codecContext, nullptr);
                    if (flushResult < 0 && flushResult != AVERROR_EOF && flushResult != AVERROR(EAGAIN)) {
                        if (errorMessage != nullptr) {
                            *errorMessage = std::string("Failed to flush the audio decoder: ") + AvErrorToString(flushResult);
                        }
                        return AudioDecoderReadResult::Error;
                    }
                    continue;
                }
                return AudioDecoderReadResult::Eof;
            }
            if (packetReadResult == PacketQueueReadResult::Error) {
                if (errorMessage != nullptr) {
                    *errorMessage = packetError;
                }
                return AudioDecoderReadResult::Error;
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
        decoder = nullptr;
        flushSent = false;
        opened = false;
        streamInfo = AudioDecoderStreamInfo{};
    }

    AudioDecoderStreamInfo streamInfo{};
    AVCodecContext *codecContext = nullptr;
    const AVCodec *decoder = nullptr;
    SwrContext *swrContext = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    AVChannelLayout outputChannelLayout{};
    bool flushSent = false;
    bool opened = false;
    int64_t decodedFrameCount = 0;
};

FfmpegAudioDecoder::FfmpegAudioDecoder()
    : impl_(std::make_unique<Impl>()) {}

FfmpegAudioDecoder::~FfmpegAudioDecoder() = default;

std::string FfmpegAudioDecoder::Open(
    const AudioPacketStreamInfo &packetStreamInfo,
    const AVCodecParameters *codecParameters,
    AVRational packetTimeBase
) {
    return impl_->Open(packetStreamInfo, codecParameters, packetTimeBase);
}

AudioDecoderReadResult FfmpegAudioDecoder::ReadNextPcmBlock(
    PacketQueue *packetQueue,
    AudioDecoderPcmBlock *block,
    std::string *errorMessage
) {
    return impl_->ReadNextPcmBlock(packetQueue, block, errorMessage);
}

const AudioDecoderStreamInfo &FfmpegAudioDecoder::GetStreamInfo() const {
    return impl_->streamInfo;
}

int64_t FfmpegAudioDecoder::GetDecodedFrameCount() const {
    return impl_->decodedFrameCount;
}
