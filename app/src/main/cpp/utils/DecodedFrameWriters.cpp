#include "DecodedFrameWriters.h"

#include <cstdint>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

extern "C" {
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

std::string ErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

const char *SafeString(const char *value, const char *fallback = "unknown") {
    return value != nullptr ? value : fallback;
}

}  // namespace

namespace tutorial_utils {

struct AudioPcmWriter::Impl {
    ~Impl() {
        if (swrContext != nullptr) {
            swr_free(&swrContext);
        }
        av_channel_layout_uninit(&outputLayout);
    }

    std::string Open(AVCodecContext *codecContext, const std::string &path) {
        if (codecContext->ch_layout.nb_channels <= 0) {
            av_channel_layout_default(&outputLayout, 2);
        } else {
            const int result = av_channel_layout_copy(&outputLayout, &codecContext->ch_layout);
            if (result < 0) {
                return "av_channel_layout_copy failed: " + ErrorToString(result);
            }
        }
        outputSampleRate = codecContext->sample_rate > 0 ? codecContext->sample_rate : 44100;
        outputChannels = outputLayout.nb_channels;

        int result = swr_alloc_set_opts2(
            &swrContext,
            &outputLayout,
            AV_SAMPLE_FMT_S16,
            outputSampleRate,
            &codecContext->ch_layout,
            codecContext->sample_fmt,
            codecContext->sample_rate,
            0,
            nullptr
        );
        if (result < 0 || swrContext == nullptr) {
            return "swr_alloc_set_opts2 failed: " + ErrorToString(result);
        }

        result = swr_init(swrContext);
        if (result < 0) {
            return "swr_init failed: " + ErrorToString(result);
        }

        file.open(path, std::ios::binary);
        if (!file.is_open()) {
            return "failed to open output pcm file: " + path;
        }
        pathValue = path;
        return {};
    }

    int WriteFrame(const AVFrame *frame, std::string *error) {
        const int64_t delay = swr_get_delay(swrContext, frame->sample_rate);
        const int outSamples = static_cast<int>(av_rescale_rnd(
            delay + frame->nb_samples,
            outputSampleRate,
            frame->sample_rate,
            AV_ROUND_UP
        ));
        const int bufferSize = av_samples_get_buffer_size(
            nullptr,
            outputChannels,
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
            swrContext,
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

        const int bytes = converted * outputChannels *
            av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        file.write(reinterpret_cast<const char *>(buffer.data()), bytes);
        return bytes;
    }

    std::string Summary() const {
        std::ostringstream oss;
        oss << "output_file: " << pathValue << '\n';
        oss << "output_format: s16le packed PCM\n";
        oss << "output_sample_rate: " << outputSampleRate << '\n';
        oss << "output_channels: " << outputChannels << '\n';
        oss << "play_hint: ffplay -f s16le -ar " << outputSampleRate
            << " -ac " << outputChannels << " " << pathValue << '\n';
        return oss.str();
    }

    SwrContext *swrContext = nullptr;
    AVChannelLayout outputLayout{};
    int outputSampleRate = 0;
    int outputChannels = 0;
    std::ofstream file;
    std::string pathValue;
};

AudioPcmWriter::AudioPcmWriter() : impl_(std::make_unique<Impl>()) {}

AudioPcmWriter::~AudioPcmWriter() = default;

std::string AudioPcmWriter::Open(AVCodecContext *codecContext, const std::string &path) {
    return impl_->Open(codecContext, path);
}

int AudioPcmWriter::WriteFrame(const AVFrame *frame, std::string *error) {
    return impl_->WriteFrame(frame, error);
}

std::string AudioPcmWriter::Summary() const {
    return impl_->Summary();
}

struct VideoYuvWriter::Impl {
    ~Impl() {
        if (dstData[0] != nullptr) {
            av_freep(&dstData[0]);
        }
        if (swsContext != nullptr) {
            sws_freeContext(swsContext);
        }
    }

    std::string Open(AVCodecContext *codecContext, const std::string &path) {
        width = codecContext->width;
        height = codecContext->height;
        sourceFormat = codecContext->pix_fmt;
        swsContext = sws_getContext(
            width,
            height,
            sourceFormat,
            width,
            height,
            AV_PIX_FMT_YUV420P,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr
        );
        if (swsContext == nullptr) {
            return "sws_getContext failed";
        }

        const int imageSize = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width, height, 1);
        if (imageSize <= 0) {
            return "av_image_get_buffer_size failed: " + ErrorToString(imageSize);
        }
        imageBuffer.resize(static_cast<size_t>(imageSize));

        const int result = av_image_alloc(dstData, dstLinesize, width, height, AV_PIX_FMT_YUV420P, 1);
        if (result < 0) {
            return "av_image_alloc failed: " + ErrorToString(result);
        }

        file.open(path, std::ios::binary);
        if (!file.is_open()) {
            return "failed to open output yuv file: " + path;
        }
        pathValue = path;
        return {};
    }

    int WriteFrame(const AVFrame *frame, std::string *error) {
        const int scaledHeight = sws_scale(
            swsContext,
            frame->data,
            frame->linesize,
            0,
            height,
            dstData,
            dstLinesize
        );
        if (scaledHeight <= 0) {
            if (error != nullptr) {
                *error = "sws_scale failed";
            }
            return -1;
        }

        const int copied = av_image_copy_to_buffer(
            imageBuffer.data(),
            static_cast<int>(imageBuffer.size()),
            dstData,
            dstLinesize,
            AV_PIX_FMT_YUV420P,
            width,
            height,
            1
        );
        if (copied < 0) {
            if (error != nullptr) {
                *error = "av_image_copy_to_buffer failed: " + ErrorToString(copied);
            }
            return -1;
        }

        file.write(reinterpret_cast<const char *>(imageBuffer.data()), copied);
        return copied;
    }

    std::string Summary() const {
        std::ostringstream oss;
        oss << "output_file: " << pathValue << '\n';
        oss << "output_format: yuv420p raw video\n";
        oss << "output_width: " << width << '\n';
        oss << "output_height: " << height << '\n';
        oss << "source_pixel_format: " << SafeString(av_get_pix_fmt_name(sourceFormat)) << '\n';
        oss << "play_hint: ffplay -f rawvideo -pixel_format yuv420p -video_size "
            << width << "x" << height << " " << pathValue << '\n';
        return oss.str();
    }

    SwsContext *swsContext = nullptr;
    int width = 0;
    int height = 0;
    AVPixelFormat sourceFormat = AV_PIX_FMT_NONE;
    uint8_t *dstData[4] = {nullptr, nullptr, nullptr, nullptr};
    int dstLinesize[4] = {0, 0, 0, 0};
    std::vector<uint8_t> imageBuffer;
    std::ofstream file;
    std::string pathValue;
};

VideoYuvWriter::VideoYuvWriter() : impl_(std::make_unique<Impl>()) {}

VideoYuvWriter::~VideoYuvWriter() = default;

std::string VideoYuvWriter::Open(AVCodecContext *codecContext, const std::string &path) {
    return impl_->Open(codecContext, path);
}

int VideoYuvWriter::WriteFrame(const AVFrame *frame, std::string *error) {
    return impl_->WriteFrame(frame, error);
}

std::string VideoYuvWriter::Summary() const {
    return impl_->Summary();
}

}  // namespace tutorial_utils
