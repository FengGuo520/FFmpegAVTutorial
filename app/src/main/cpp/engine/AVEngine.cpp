//
// Created by 18790 on 2026/6/10.
//

#include <jni.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "NativeLog.h"

#include <sstream>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/error.h"
#include "libavutil/mathematics.h"
}



extern "C" {
jclass NativeInstance;

struct LiveFlvMuxer {
    AVFormatContext *outputContext = nullptr;
    AVStream *videoStream = nullptr;
    AVStream *audioStream = nullptr;
    std::string outputPath;
    int frameRate = 30;
    int sampleRate = 44100;
    int videoPacketCount = 0;
    int audioPacketCount = 0;
    bool headerWritten = false;
};

struct InstanceHolder {
    int keepalive = 1;
    std::mutex liveMuxerMutex;
    std::unique_ptr<LiveFlvMuxer> liveMuxer;
};


}

namespace {

    std::string VersionToString(unsigned version) {
        std::ostringstream oss;
        oss << ((version >> 16) & 0xFF)
            << '.'
            << ((version >> 8) & 0xFF)
            << '.'
            << (version & 0xFF);
        return oss.str();
    }

    const char *MediaTypeName(AVMediaType mediaType) {
        switch (mediaType) {
            case AVMEDIA_TYPE_VIDEO:
                return "video";
            case AVMEDIA_TYPE_AUDIO:
                return "audio";
            case AVMEDIA_TYPE_SUBTITLE:
                return "subtitle";
            case AVMEDIA_TYPE_DATA:
                return "data";
            case AVMEDIA_TYPE_ATTACHMENT:
                return "attachment";
            default:
                return "other";
        }
    }

    void AppendProtocols(std::ostringstream &oss, const char *title, int output) {
        void *opaque = nullptr;
        const char *name = nullptr;
        int count = 0;

        oss << title << ":\n";
        while ((name = avio_enum_protocols(&opaque, output)) != nullptr) {
            oss << "  " << name << '\n';
            ++count;
        }
        oss << "count=" << count << "\n\n";
    }

    void AppendFormats(std::ostringstream &oss, const char *title, bool output) {
        void *opaque = nullptr;
        int count = 0;

        oss << title << ":\n";
        if (output) {
            const AVOutputFormat *format = nullptr;
            while ((format = av_muxer_iterate(&opaque)) != nullptr) {
                oss << "  " << format->name;
                if (format->long_name != nullptr) {
                    oss << " - " << format->long_name;
                }
                oss << '\n';
                ++count;
            }
        } else {
            const AVInputFormat *format = nullptr;
            while ((format = av_demuxer_iterate(&opaque)) != nullptr) {
                oss << "  " << format->name;
                if (format->long_name != nullptr) {
                    oss << " - " << format->long_name;
                }
                oss << '\n';
                ++count;
            }
        }
        oss << "count=" << count << "\n\n";
    }

    void AppendCodecs(std::ostringstream &oss, const char *title, bool encoder, AVMediaType mediaType) {
        void *opaque = nullptr;
        const AVCodec *codec = nullptr;
        int count = 0;

        oss << title << " (" << MediaTypeName(mediaType) << "):\n";
        while ((codec = av_codec_iterate(&opaque)) != nullptr) {
            bool matchesRole = encoder ? av_codec_is_encoder(codec) != 0 : av_codec_is_decoder(codec) != 0;
            if (!matchesRole || codec->type != mediaType) {
                continue;
            }

            oss << "  " << codec->name;
            if (codec->long_name != nullptr) {
                oss << " - " << codec->long_name;
            }
            oss << '\n';
            ++count;
        }
        oss << "count=" << count << "\n\n";
    }

    std::string BuildFFmpegInfo() {
        std::ostringstream oss;

        oss << "FFmpeg Runtime Info\n"
            << "===================\n\n";

        oss << "libavutil\n"
            << "  version: " << av_version_info() << '\n'
            << "  version_int: " << VersionToString(avutil_version()) << '\n'
            << "  license: " << avutil_license() << "\n\n";

        oss << "libavcodec\n"
            << "  version_int: " << VersionToString(avcodec_version()) << '\n'
            << "  license: " << avcodec_license() << "\n\n";

        oss << "libavformat\n"
            << "  version_int: " << VersionToString(avformat_version()) << '\n'
            << "  license: " << avformat_license() << "\n\n";

        oss << "configuration\n"
            << "  avutil: " << avutil_configuration() << "\n"
            << "  avcodec: " << avcodec_configuration() << "\n"
            << "  avformat: " << avformat_configuration() << "\n\n";

        AppendProtocols(oss, "input protocols", 0);
        AppendProtocols(oss, "output protocols", 1);
        AppendFormats(oss, "demuxers", false);
        AppendFormats(oss, "muxers", true);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_VIDEO);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_AUDIO);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_SUBTITLE);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_VIDEO);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_AUDIO);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_SUBTITLE);

        return oss.str();
    }

    std::string AvErrorToString(int errorCode) {
        char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_make_error_string(buffer, sizeof(buffer), errorCode);
        return buffer;
    }

    AVRational NormalizeTimeBase(AVRational timeBase, AVRational fallback) {
        if (timeBase.num <= 0 || timeBase.den <= 0) {
            return fallback;
        }
        return timeBase;
    }

    int FindFirstStreamIndex(AVFormatContext *formatContext, AVMediaType mediaType) {
        for (unsigned int index = 0; index < formatContext->nb_streams; ++index) {
            if (formatContext->streams[index]->codecpar->codec_type == mediaType) {
                return static_cast<int>(index);
            }
        }
        return -1;
    }

    int64_t GuessVideoPacketDuration(AVStream *stream) {
        AVRational timeBase = NormalizeTimeBase(stream->time_base, AVRational{1, 90000});
        AVRational frameRate = stream->avg_frame_rate;
        if (frameRate.num > 0 && frameRate.den > 0) {
            int64_t duration = av_rescale_q(1, AVRational{frameRate.den, frameRate.num}, timeBase);
            if (duration > 0) {
                return duration;
            }
        }
        return 1;
    }

    int64_t GuessAudioPacketDuration(AVStream *stream) {
        int sampleRate = stream->codecpar->sample_rate > 0 ? stream->codecpar->sample_rate : 44100;
        AVRational timeBase = NormalizeTimeBase(stream->time_base, AVRational{1, sampleRate});
        int64_t duration = av_rescale_q(1024, AVRational{1, sampleRate}, timeBase);
        return duration > 0 ? duration : 1024;
    }

    int64_t PacketSortTimestamp(const AVPacket &packet, AVRational timeBase) {
        int64_t timestamp = packet.dts != AV_NOPTS_VALUE ? packet.dts : packet.pts;
        if (timestamp == AV_NOPTS_VALUE) {
            return INT64_MAX;
        }
        return av_rescale_q(timestamp, NormalizeTimeBase(timeBase, AVRational{1, 1000}), AV_TIME_BASE_Q);
    }


    struct PacketSource {
        const char *label = nullptr;
        AVFormatContext *formatContext = nullptr;
        AVStream *inputStream = nullptr;
        AVStream *outputStream = nullptr;
        int streamIndex = -1;
        AVPacket packet{};
        bool hasPacket = false;
        bool finished = false;
        int64_t nextFallbackPts = 0;
        int64_t fallbackDuration = 1;
        int packetCount = 0;
    };

    int ReadNextPacket(PacketSource &source) {
        while (true) {
            int result = av_read_frame(source.formatContext, &source.packet);
            if (result == AVERROR_EOF) {
                source.finished = true;
                return 0;
            }
            if (result < 0) {
                return result;
            }
            if (source.packet.stream_index != source.streamIndex) {
                av_packet_unref(&source.packet);
                continue;
            }

            if (source.packet.duration <= 0) {
                source.packet.duration = source.fallbackDuration;
            }

            int64_t baseTimestamp =
                source.packet.pts != AV_NOPTS_VALUE ? source.packet.pts : source.packet.dts;
            if (baseTimestamp == AV_NOPTS_VALUE) {
                baseTimestamp = source.nextFallbackPts;
                source.packet.pts = baseTimestamp;
            }
            if (source.packet.dts == AV_NOPTS_VALUE) {
                source.packet.dts = baseTimestamp;
            }
            if (source.packet.pts == AV_NOPTS_VALUE) {
                source.packet.pts = source.packet.dts;
            }

            source.nextFallbackPts = std::max(source.packet.pts, source.packet.dts);
            source.nextFallbackPts += source.packet.duration > 0 ? source.packet.duration : source.fallbackDuration;
            source.hasPacket = true;
            return 0;
        }
    }

    int WritePacket(AVFormatContext *outputContext, PacketSource &source) {
        av_packet_rescale_ts(
            &source.packet,
            NormalizeTimeBase(source.inputStream->time_base, AVRational{1, 1000}),
            NormalizeTimeBase(source.outputStream->time_base, AVRational{1, 1000})
        );
        source.packet.stream_index = source.outputStream->index;
        source.packet.pos = -1;
        int result = av_interleaved_write_frame(outputContext, &source.packet);
        av_packet_unref(&source.packet);
        source.hasPacket = false;
        if (result >= 0) {
            source.packetCount += 1;
        }
        return result;
    }

    std::string MuxToFlv(
        const std::string &videoPath,
        const std::string &audioPath,
        const std::string &outputPath
    ) {
        AVFormatContext *videoContext = nullptr;
        AVFormatContext *audioContext = nullptr;
        AVFormatContext *outputContext = nullptr;
        AVDictionary *videoOptions = nullptr;
        bool headerWritten = false;
        int result = 0;

        PacketSource videoSource{};
        PacketSource audioSource{};

        auto cleanup = [&]() {
            if (videoSource.hasPacket) {
                av_packet_unref(&videoSource.packet);
                videoSource.hasPacket = false;
            }
            if (audioSource.hasPacket) {
                av_packet_unref(&audioSource.packet);
                audioSource.hasPacket = false;
            }
            av_dict_free(&videoOptions);
            if (videoContext != nullptr) {
                avformat_close_input(&videoContext);
            }
            if (audioContext != nullptr) {
                avformat_close_input(&audioContext);
            }
            if (outputContext != nullptr) {
                if (!(outputContext->oformat->flags & AVFMT_NOFILE) && outputContext->pb != nullptr) {
                    avio_closep(&outputContext->pb);
                }
                avformat_free_context(outputContext);
            }
            if (result < 0) {
                std::remove(outputPath.c_str());
            }
        };

        av_dict_set(&videoOptions, "framerate", "30", 0);
        result = avformat_open_input(
            &videoContext,
            videoPath.c_str(),
            av_find_input_format("h264"),
            &videoOptions
        );
        if (result < 0) {
            cleanup();
            return "ERROR: failed to open H.264 input: " + AvErrorToString(result);
        }

        result = avformat_find_stream_info(videoContext, nullptr);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to read H.264 stream info: " + AvErrorToString(result);
        }

        result = avformat_open_input(
            &audioContext,
            audioPath.c_str(),
            av_find_input_format("aac"),
            nullptr
        );
        if (result < 0) {
            cleanup();
            return "ERROR: failed to open AAC input: " + AvErrorToString(result);
        }

        result = avformat_find_stream_info(audioContext, nullptr);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to read AAC stream info: " + AvErrorToString(result);
        }

        videoSource.label = "video";
        videoSource.formatContext = videoContext;
        videoSource.streamIndex = FindFirstStreamIndex(videoContext, AVMEDIA_TYPE_VIDEO);
        if (videoSource.streamIndex < 0) {
            result = AVERROR_STREAM_NOT_FOUND;
            cleanup();
            return "ERROR: no video stream found in H.264 input.";
        }
        videoSource.inputStream = videoContext->streams[videoSource.streamIndex];
        videoSource.fallbackDuration = GuessVideoPacketDuration(videoSource.inputStream);

        audioSource.label = "audio";
        audioSource.formatContext = audioContext;
        audioSource.streamIndex = FindFirstStreamIndex(audioContext, AVMEDIA_TYPE_AUDIO);
        if (audioSource.streamIndex < 0) {
            result = AVERROR_STREAM_NOT_FOUND;
            cleanup();
            return "ERROR: no audio stream found in AAC input.";
        }
        audioSource.inputStream = audioContext->streams[audioSource.streamIndex];
        audioSource.fallbackDuration = GuessAudioPacketDuration(audioSource.inputStream);

        result = avformat_alloc_output_context2(&outputContext, nullptr, "flv", outputPath.c_str());
        if (result < 0 || outputContext == nullptr) {
            result = result < 0 ? result : AVERROR_UNKNOWN;
            cleanup();
            return "ERROR: failed to allocate FLV output context: " + AvErrorToString(result);
        }

        videoSource.outputStream = avformat_new_stream(outputContext, nullptr);
        audioSource.outputStream = avformat_new_stream(outputContext, nullptr);
        if (videoSource.outputStream == nullptr || audioSource.outputStream == nullptr) {
            result = AVERROR(ENOMEM);
            cleanup();
            return "ERROR: failed to create FLV output streams: " + AvErrorToString(result);
        }

        result = avcodec_parameters_copy(videoSource.outputStream->codecpar, videoSource.inputStream->codecpar);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to copy video codec parameters: " + AvErrorToString(result);
        }
        result = avcodec_parameters_copy(audioSource.outputStream->codecpar, audioSource.inputStream->codecpar);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to copy audio codec parameters: " + AvErrorToString(result);
        }

        videoSource.outputStream->codecpar->codec_tag = 0;
        audioSource.outputStream->codecpar->codec_tag = 0;
        videoSource.outputStream->time_base =
            NormalizeTimeBase(videoSource.inputStream->time_base, AVRational{1, 90000});
        audioSource.outputStream->time_base =
            NormalizeTimeBase(audioSource.inputStream->time_base, AVRational{1, 44100});

        if (!(outputContext->oformat->flags & AVFMT_NOFILE)) {
            result = avio_open(&outputContext->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
            if (result < 0) {
                cleanup();
                return "ERROR: failed to open FLV output file: " + AvErrorToString(result);
            }
        }

        result = avformat_write_header(outputContext, nullptr);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to write FLV header: " + AvErrorToString(result);
        }
        headerWritten = true;

        result = ReadNextPacket(videoSource);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to read the first video packet: " + AvErrorToString(result);
        }

        result = ReadNextPacket(audioSource);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to read the first audio packet: " + AvErrorToString(result);
        }

        while (videoSource.hasPacket || audioSource.hasPacket) {
            PacketSource *selectedSource = nullptr;
            if (!audioSource.hasPacket) {
                selectedSource = &videoSource;
            } else if (!videoSource.hasPacket) {
                selectedSource = &audioSource;
            } else if (
                PacketSortTimestamp(videoSource.packet, videoSource.inputStream->time_base) <=
                PacketSortTimestamp(audioSource.packet, audioSource.inputStream->time_base)
            ) {
                selectedSource = &videoSource;
            } else {
                selectedSource = &audioSource;
            }

            result = WritePacket(outputContext, *selectedSource);
            if (result < 0) {
                cleanup();
                return std::string("ERROR: failed while muxing ")
                    + selectedSource->label
                    + " packet: "
                    + AvErrorToString(result);
            }

            result = ReadNextPacket(*selectedSource);
            if (result < 0) {
                cleanup();
                return std::string("ERROR: failed while reading next ")
                    + selectedSource->label
                    + " packet: "
                    + AvErrorToString(result);
            }
        }

        result = av_write_trailer(outputContext);
        if (result < 0) {
            cleanup();
            return "ERROR: failed to finalize FLV file: " + AvErrorToString(result);
        }
        headerWritten = false;

        std::ostringstream oss;
        oss << "FLV mux completed.\n"
            << "video packets: " << videoSource.packetCount << '\n'
            << "audio packets: " << audioSource.packetCount << '\n'
            << "output: " << outputPath;

        result = 0;
        cleanup();
        return oss.str();
    }

    std::vector<uint8_t> JByteArrayToVector(JNIEnv *env, jbyteArray byteArray) {
        if (byteArray == nullptr) {
            return {};
        }
        jsize length = env->GetArrayLength(byteArray);
        if (length <= 0) {
            return {};
        }
        std::vector<uint8_t> data(static_cast<size_t>(length));
        env->GetByteArrayRegion(
            byteArray,
            0,
            length,
            reinterpret_cast<jbyte *>(data.data())
        );
        return data;
    }

    void SetCodecExtradata(AVCodecParameters *codecParameters, const std::vector<uint8_t> &data) {
        if (data.empty()) {
            return;
        }
        auto *buffer = static_cast<uint8_t *>(av_malloc(data.size() + AV_INPUT_BUFFER_PADDING_SIZE));
        std::copy(data.begin(), data.end(), buffer);
        std::fill(
            buffer + data.size(),
            buffer + data.size() + AV_INPUT_BUFFER_PADDING_SIZE,
            0
        );
        codecParameters->extradata = buffer;
        codecParameters->extradata_size = static_cast<int>(data.size());
    }

    void ReleaseLiveFlvMuxerResources(LiveFlvMuxer *muxer, bool removeOutputFile) {
        if (muxer == nullptr || muxer->outputContext == nullptr) {
            return;
        }
        if (!(muxer->outputContext->oformat->flags & AVFMT_NOFILE) &&
            muxer->outputContext->pb != nullptr) {
            avio_closep(&muxer->outputContext->pb);
        }
        if (muxer->outputContext != nullptr) {
            avformat_free_context(muxer->outputContext);
            muxer->outputContext = nullptr;
        }
        if (removeOutputFile) {
            std::remove(muxer->outputPath.c_str());
        }
    }


    std::string OpenLiveFlvMuxer(
        LiveFlvMuxer *muxer,
        const std::string &outputPath,
        int width,
        int height,
        int frameRate,
        int videoBitrate,
        const std::vector<uint8_t> &videoCsd0,
        const std::vector<uint8_t> &videoCsd1,
        int sampleRate,
        int channelCount,
        int audioBitrate,
        const std::vector<uint8_t> &audioSpecificConfig
    ) {
        if (videoCsd0.empty()) {
            return "ERROR: missing H.264 csd-0 (SPS) data.";
        }
        if (audioSpecificConfig.empty()) {
            return "ERROR: missing AAC audio specific config data.";
        }

        muxer->outputPath = outputPath;
        muxer->frameRate = std::max(frameRate, 1);
        muxer->sampleRate = std::max(sampleRate, 1);

        int result = avformat_alloc_output_context2(
            &muxer->outputContext,
            nullptr,
            "flv",
            outputPath.c_str()
        );
        if (result < 0 || muxer->outputContext == nullptr) {
            return "ERROR: failed to allocate live FLV output context: " +
                AvErrorToString(result < 0 ? result : AVERROR_UNKNOWN);
        }

        muxer->videoStream = avformat_new_stream(muxer->outputContext, nullptr);
        muxer->audioStream = avformat_new_stream(muxer->outputContext, nullptr);
        if (muxer->videoStream == nullptr || muxer->audioStream == nullptr) {
            ReleaseLiveFlvMuxerResources(muxer, true);
            return "ERROR: failed to create live FLV streams.";
        }

        muxer->videoStream->time_base = AVRational{1, AV_TIME_BASE};
        muxer->audioStream->time_base = AVRational{1, AV_TIME_BASE};

        AVCodecParameters *videoParams = muxer->videoStream->codecpar;
        videoParams->codec_type = AVMEDIA_TYPE_VIDEO;
        videoParams->codec_id = AV_CODEC_ID_H264;
        videoParams->width = width;
        videoParams->height = height;
        videoParams->bit_rate = videoBitrate;
        videoParams->codec_tag = 0;

        std::vector<uint8_t> videoExtradata;
        videoExtradata.reserve(videoCsd0.size() + videoCsd1.size());
        videoExtradata.insert(videoExtradata.end(), videoCsd0.begin(), videoCsd0.end());
        videoExtradata.insert(videoExtradata.end(), videoCsd1.begin(), videoCsd1.end());
        SetCodecExtradata(videoParams, videoExtradata);

        AVCodecParameters *audioParams = muxer->audioStream->codecpar;
        audioParams->codec_type = AVMEDIA_TYPE_AUDIO;
        audioParams->codec_id = AV_CODEC_ID_AAC;
        audioParams->sample_rate = sampleRate;
        audioParams->bit_rate = audioBitrate;
        audioParams->codec_tag = 0;
        av_channel_layout_default(&audioParams->ch_layout, channelCount);
        SetCodecExtradata(audioParams, audioSpecificConfig);

        if (!(muxer->outputContext->oformat->flags & AVFMT_NOFILE)) {
            result = avio_open(&muxer->outputContext->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
            if (result < 0) {
                ReleaseLiveFlvMuxerResources(muxer, true);
                return "ERROR: failed to open live FLV output file: " + AvErrorToString(result);
            }
        }

        result = avformat_write_header(muxer->outputContext, nullptr);
        if (result < 0) {
            ReleaseLiveFlvMuxerResources(muxer, true);
            return "ERROR: failed to write live FLV header: " + AvErrorToString(result);
        }

        muxer->headerWritten = true;
        return "OK: live FLV muxer started.";
    }

    int WriteLivePacket(
        LiveFlvMuxer *muxer,
        const uint8_t *data,
        int size,
        int64_t ptsUs,
        int flags,
        bool isVideo
    ) {
        if (muxer == nullptr || muxer->outputContext == nullptr || size <= 0 || data == nullptr) {
            return AVERROR(EINVAL);
        }

        AVStream *stream = isVideo ? muxer->videoStream : muxer->audioStream;
        AVRational inputTimeBase{1, AV_TIME_BASE};
        int64_t durationUs =
            isVideo
                ? (AV_TIME_BASE / std::max(muxer->frameRate, 1))
                : av_rescale_q(1024, AVRational{1, muxer->sampleRate}, inputTimeBase);

        AVPacket packet;
        av_init_packet(&packet);
        int result = av_new_packet(&packet, size);
        if (result < 0) {
            return result;
        }

        std::memcpy(packet.data, data, static_cast<size_t>(size));
        packet.stream_index = stream->index;
        packet.pts = av_rescale_q(ptsUs, inputTimeBase, stream->time_base);
        packet.dts = packet.pts;
        packet.duration = av_rescale_q(durationUs, inputTimeBase, stream->time_base);
        packet.pos = -1;
        if (flags & 1) {
            packet.flags |= AV_PKT_FLAG_KEY;
        }

        result = av_interleaved_write_frame(muxer->outputContext, &packet);
        av_packet_unref(&packet);
        if (result >= 0) {
            if (isVideo) {
                muxer->videoPacketCount += 1;
            } else {
                muxer->audioPacketCount += 1;
            }
        }
        return result;
    }

    std::string CloseLiveFlvMuxer(std::unique_ptr<LiveFlvMuxer> &muxer) {
        if (muxer == nullptr) {
            return "Live FLV muxer was not running.";
        }

        int result = 0;
        if (muxer->headerWritten && muxer->outputContext != nullptr) {
            result = av_write_trailer(muxer->outputContext);
        }

        std::ostringstream oss;
        if (result < 0) {
            oss << "ERROR: failed to finalize live FLV file: " << AvErrorToString(result);
            ReleaseLiveFlvMuxerResources(muxer.get(), true);
            muxer.reset();
            return oss.str();
        }

        oss << "Live FLV mux completed.\n"
            << "video packets: " << muxer->videoPacketCount << '\n'
            << "audio packets: " << muxer->audioPacketCount << '\n'
            << "output: " << muxer->outputPath;

        ReleaseLiveFlvMuxerResources(muxer.get(), false);
        muxer.reset();
        return oss.str();
    }

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_makeNativeInstance(JNIEnv *env, jobject obj,
                                                                         jobject instance) {
    auto *holder = new InstanceHolder();
    NATIVE_LOGI_TAG("AVEngine", "makeNativeInstance holder=%p", holder);
    return reinterpret_cast<jlong>(holder);
}



extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_getInfo(JNIEnv *env, jobject obj,jlong nativeHandle) {
    std::string info = BuildFFmpegInfo();
    return env->NewStringUTF(info.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_muxToFlv(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jstring videoPath,
    jstring audioPath,
    jstring outputPath
) {
    const char *videoPathChars = env->GetStringUTFChars(videoPath, nullptr);
    const char *audioPathChars = env->GetStringUTFChars(audioPath, nullptr);
    const char *outputPathChars = env->GetStringUTFChars(outputPath, nullptr);

    NATIVE_LOGI_TAG(
        "AVEngine",
        "muxToFlv video=%s audio=%s output=%s",
        videoPathChars,
        audioPathChars,
        outputPathChars
    );
    std::string result = MuxToFlv(videoPathChars, audioPathChars, outputPathChars);

    env->ReleaseStringUTFChars(videoPath, videoPathChars);
    env->ReleaseStringUTFChars(audioPath, audioPathChars);
    env->ReleaseStringUTFChars(outputPath, outputPathChars);

    NATIVE_LOGI_TAG("AVEngine", "muxToFlv result=%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_openLiveFlvMuxer(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jstring outputPath,
    jint width,
    jint height,
    jint frameRate,
    jint videoBitrate,
    jbyteArray videoCsd0,
    jbyteArray videoCsd1,
    jint sampleRate,
    jint channelCount,
    jint audioBitrate,
    jbyteArray audioSpecificConfig
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        NATIVE_LOGE_TAG("AVEngine", "openLiveFlvMuxer failed: nativeHandle is null");
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    const char *outputPathChars = env->GetStringUTFChars(outputPath, nullptr);
    std::vector<uint8_t> videoConfig0 = JByteArrayToVector(env, videoCsd0);
    std::vector<uint8_t> videoConfig1 = JByteArrayToVector(env, videoCsd1);
    std::vector<uint8_t> audioConfig = JByteArrayToVector(env, audioSpecificConfig);

    std::lock_guard<std::mutex> lock(holder->liveMuxerMutex);
    if (holder->liveMuxer != nullptr) {
        CloseLiveFlvMuxer(holder->liveMuxer);
    }
    holder->liveMuxer = std::make_unique<LiveFlvMuxer>();
    NATIVE_LOGI_TAG(
        "AVEngine",
        "openLiveFlvMuxer output=%s size=%dx%d frameRate=%d videoBitrate=%d sampleRate=%d channelCount=%d audioBitrate=%d",
        outputPathChars,
        width,
        height,
        frameRate,
        videoBitrate,
        sampleRate,
        channelCount,
        audioBitrate
    );
    std::string result = OpenLiveFlvMuxer(
        holder->liveMuxer.get(),
        outputPathChars,
        width,
        height,
        frameRate,
        videoBitrate,
        videoConfig0,
        videoConfig1,
        sampleRate,
        channelCount,
        audioBitrate,
        audioConfig
    );
    if (result.rfind("OK:", 0) != 0) {
        NATIVE_LOGE_TAG("AVEngine", "openLiveFlvMuxer result=%s", result.c_str());
        holder->liveMuxer.reset();
    } else {
        NATIVE_LOGI_TAG("AVEngine", "openLiveFlvMuxer result=%s", result.c_str());
    }

    env->ReleaseStringUTFChars(outputPath, outputPathChars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_writeLiveVideoPacket(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jbyteArray data,
    jlong ptsUs,
    jint flags
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return AVERROR(EINVAL);
    }

    std::vector<uint8_t> packetData = JByteArrayToVector(env, data);
    std::lock_guard<std::mutex> lock(holder->liveMuxerMutex);
    return WriteLivePacket(
        holder->liveMuxer.get(),
        packetData.data(),
        static_cast<int>(packetData.size()),
        ptsUs,
        flags,
        true
    );
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_writeLiveAudioPacket(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jbyteArray data,
    jlong ptsUs,
    jint flags
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return AVERROR(EINVAL);
    }

    std::vector<uint8_t> packetData = JByteArrayToVector(env, data);
    std::lock_guard<std::mutex> lock(holder->liveMuxerMutex);
    return WriteLivePacket(
        holder->liveMuxer.get(),
        packetData.data(),
        static_cast<int>(packetData.size()),
        ptsUs,
        flags,
        false
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_closeLiveFlvMuxer(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        NATIVE_LOGE_TAG("AVEngine", "closeLiveFlvMuxer failed: nativeHandle is null");
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    std::lock_guard<std::mutex> lock(holder->liveMuxerMutex);
    std::string result = CloseLiveFlvMuxer(holder->liveMuxer);
    NATIVE_LOGI_TAG("AVEngine", "closeLiveFlvMuxer result=%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}




