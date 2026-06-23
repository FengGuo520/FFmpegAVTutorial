//
// Created by 18790 on 2026/6/10.
//

#include <jni.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <sstream>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/audio_fifo.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/mathematics.h"
#include "libavutil/opt.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
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

struct SoftAacEncoder {
    AVFormatContext *outputContext = nullptr;
    AVStream *audioStream = nullptr;
    AVCodecContext *codecContext = nullptr;
    SwrContext *swrContext = nullptr;
    AVAudioFifo *audioFifo = nullptr;
    AVChannelLayout inputChannelLayout{};
    std::string outputPath;
    int inputSampleRate = 44100;
    int inputChannelCount = 2;
    int bitrate = 64000;
    int packetCount = 0;
    int64_t nextPts = 0;
    bool headerWritten = false;
};

struct SoftVideoEncoder {
    AVCodecContext *codecContext = nullptr;
    FILE *outputFile = nullptr;
    std::string outputPath;
    int width = 0;
    int height = 0;
    int frameRate = 30;
    int bitrate = 2000000;
    int iFrameInterval = 2;
    int frameCount = 0;
    int packetCount = 0;
    int64_t lastFramePts = AV_NOPTS_VALUE;
};

struct InstanceHolder {
    int keepalive = 1;
    std::mutex liveMuxerMutex;
    std::unique_ptr<LiveFlvMuxer> liveMuxer;
    std::mutex softAacEncoderMutex;
    std::unique_ptr<SoftAacEncoder> softAacEncoder;
    std::mutex softVideoEncoderMutex;
    std::unique_ptr<SoftVideoEncoder> softVideoEncoder;
};

jlong getInstanceHolderId(JNIEnv *env, jobject obj) {
    return env->GetLongField(obj, env->GetFieldID(NativeInstance, "nativePtr", "J"));
}

InstanceHolder *getInstanceHolder(JNIEnv *env, jobject obj) {
    return reinterpret_cast<InstanceHolder *>(getInstanceHolderId(env, obj));
}


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

    const char *H264NalUnitTypeName(int nalUnitType) {
        switch (nalUnitType) {
            case 1:
                return "non_idr_slice";
            case 2:
                return "slice_data_a";
            case 3:
                return "slice_data_b";
            case 4:
                return "slice_data_c";
            case 5:
                return "idr_slice";
            case 6:
                return "sei";
            case 7:
                return "sps";
            case 8:
                return "pps";
            case 9:
                return "aud";
            case 10:
                return "end_of_sequence";
            case 11:
                return "end_of_stream";
            case 12:
                return "filler";
            case 13:
                return "sps_extension";
            case 14:
                return "prefix_nal";
            case 15:
                return "subset_sps";
            case 19:
                return "auxiliary_slice";
            default:
                return "other";
        }
    }

    std::string H264ProfileToString(int profile) {
        if (profile == FF_PROFILE_UNKNOWN) {
            return "unknown";
        }
        const char *profileName = avcodec_profile_name(AV_CODEC_ID_H264, profile);
        if (profileName != nullptr) {
            return profileName;
        }
        return std::to_string(profile);
    }

    std::string H264LevelToString(int level) {
        if (level <= 0) {
            return "unknown";
        }
        std::ostringstream oss;
        oss << (level / 10);
        if (level % 10 != 0) {
            oss << '.' << (level % 10);
        } else {
            oss << ".0";
        }
        return oss.str();
    }

    std::string RationalToString(AVRational rational) {
        if (rational.num <= 0 || rational.den <= 0) {
            return "unknown";
        }
        std::ostringstream oss;
        oss << rational.num << '/' << rational.den;
        return oss.str();
    }

    bool FindNextStartCode(
        const std::vector<uint8_t> &data,
        size_t fromOffset,
        size_t &startOffset,
        size_t &startCodeLength
    ) {
        if (data.size() < 4 || fromOffset >= data.size()) {
            return false;
        }

        for (size_t i = fromOffset; i + 3 < data.size(); ++i) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    startOffset = i;
                    startCodeLength = 3;
                    return true;
                }
                if (i + 3 < data.size() && data[i + 2] == 0 && data[i + 3] == 1) {
                    startOffset = i;
                    startCodeLength = 4;
                    return true;
                }
            }
        }
        return false;
    }

    int CountH264AccessUnits(const std::vector<uint8_t> &fileBytes, std::string &errorMessage) {
        const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (codec == nullptr) {
            errorMessage = "H.264 decoder/parser is not available in this build.";
            return -1;
        }

        AVCodecParserContext *parser = av_parser_init(AV_CODEC_ID_H264);
        if (parser == nullptr) {
            errorMessage = "failed to create H.264 parser.";
            return -1;
        }

        AVCodecContext *codecContext = avcodec_alloc_context3(codec);
        if (codecContext == nullptr) {
            av_parser_close(parser);
            errorMessage = "failed to allocate H.264 parser context.";
            return -1;
        }

        int accessUnitCount = 0;
        size_t offset = 0;
        while (offset < fileBytes.size()) {
            uint8_t *parsedData = nullptr;
            int parsedSize = 0;
            const int chunkSize = static_cast<int>(std::min<size_t>(fileBytes.size() - offset, 4096));
            int consumed = av_parser_parse2(
                parser,
                codecContext,
                &parsedData,
                &parsedSize,
                fileBytes.data() + offset,
                chunkSize,
                AV_NOPTS_VALUE,
                AV_NOPTS_VALUE,
                0
            );
            if (consumed < 0) {
                errorMessage = "failed while parsing H.264 access units: " + AvErrorToString(consumed);
                avcodec_free_context(&codecContext);
                av_parser_close(parser);
                return -1;
            }
            if (parsedSize > 0) {
                accessUnitCount += 1;
            }
            if (consumed == 0) {
                break;
            }
            offset += static_cast<size_t>(consumed);
        }

        uint8_t *flushData = nullptr;
        int flushSize = 0;
        int flushResult = av_parser_parse2(
            parser,
            codecContext,
            &flushData,
            &flushSize,
            nullptr,
            0,
            AV_NOPTS_VALUE,
            AV_NOPTS_VALUE,
            0
        );
        if (flushResult < 0) {
            errorMessage = "failed while flushing H.264 parser: " + AvErrorToString(flushResult);
            avcodec_free_context(&codecContext);
            av_parser_close(parser);
            return -1;
        }
        if (flushSize > 0) {
            accessUnitCount += 1;
        }

        avcodec_free_context(&codecContext);
        av_parser_close(parser);
        return accessUnitCount;
    }

    std::string AnalyzeH264Stream(const std::string &filePath) {
        std::ifstream input(filePath, std::ios::binary);
        if (!input.is_open()) {
            return "ERROR: failed to open H.264 file: " + filePath;
        }

        std::vector<uint8_t> fileBytes(
            (std::istreambuf_iterator<char>(input)),
            std::istreambuf_iterator<char>()
        );
        input.close();

        if (fileBytes.empty()) {
            return "ERROR: selected H.264 file is empty.";
        }

        AVFormatContext *formatContext = nullptr;
        AVDictionary *formatOptions = nullptr;
        int result = 0;

        av_dict_set(&formatOptions, "framerate", "30", 0);
        result = avformat_open_input(
            &formatContext,
            filePath.c_str(),
            av_find_input_format("h264"),
            &formatOptions
        );
        av_dict_free(&formatOptions);
        if (result < 0 || formatContext == nullptr) {
            if (formatContext != nullptr) {
                avformat_close_input(&formatContext);
            }
            return "ERROR: failed to open H.264 stream with FFmpeg: " + AvErrorToString(result);
        }

        result = avformat_find_stream_info(formatContext, nullptr);
        if (result < 0) {
            avformat_close_input(&formatContext);
            return "ERROR: failed to read H.264 stream info: " + AvErrorToString(result);
        }

        int videoStreamIndex = FindFirstStreamIndex(formatContext, AVMEDIA_TYPE_VIDEO);
        if (videoStreamIndex < 0) {
            avformat_close_input(&formatContext);
            return "ERROR: no video stream found in H.264 file.";
        }

        AVStream *videoStream = formatContext->streams[videoStreamIndex];
        AVCodecParameters *codecParameters = videoStream->codecpar;

        std::map<int, int> nalTypeCounts;
        int nalUnitCount = 0;
        int spsCount = 0;
        int ppsCount = 0;
        int seiCount = 0;
        int audCount = 0;
        int idrCount = 0;
        int nonIdrCount = 0;

        constexpr int kMaxDetailedNalUnits = 300;
        std::vector<std::string> nalDetails;
        nalDetails.reserve(std::min<size_t>(kMaxDetailedNalUnits, fileBytes.size() / 32 + 1));

        size_t currentStart = 0;
        size_t currentStartCodeLength = 0;
        bool hasCurrent = FindNextStartCode(fileBytes, 0, currentStart, currentStartCodeLength);
        if (!hasCurrent) {
            avformat_close_input(&formatContext);
            return "ERROR: no Annex B start code was found in the selected H.264 file.";
        }

        while (hasCurrent) {
            size_t nextStart = 0;
            size_t nextStartCodeLength = 0;
            bool hasNext = FindNextStartCode(
                fileBytes,
                currentStart + currentStartCodeLength,
                nextStart,
                nextStartCodeLength
            );

            const size_t payloadOffset = currentStart + currentStartCodeLength;
            const size_t payloadEnd = hasNext ? nextStart : fileBytes.size();
            if (payloadOffset < payloadEnd) {
                const size_t payloadSize = payloadEnd - payloadOffset;
                const uint8_t nalHeader = fileBytes[payloadOffset];
                const int nalRefIdc = (nalHeader >> 5) & 0x03;
                const int nalUnitType = nalHeader & 0x1F;

                nalUnitCount += 1;
                nalTypeCounts[nalUnitType] += 1;

                if (nalUnitType == 7) {
                    spsCount += 1;
                } else if (nalUnitType == 8) {
                    ppsCount += 1;
                } else if (nalUnitType == 6) {
                    seiCount += 1;
                } else if (nalUnitType == 9) {
                    audCount += 1;
                } else if (nalUnitType == 5) {
                    idrCount += 1;
                } else if (nalUnitType == 1) {
                    nonIdrCount += 1;
                }

                if (static_cast<int>(nalDetails.size()) < kMaxDetailedNalUnits) {
                    std::ostringstream line;
                    line << "  #" << nalUnitCount
                         << " offset=" << payloadOffset
                         << " start_code_bytes=" << currentStartCodeLength
                         << " payload_bytes=" << payloadSize
                         << " type=" << nalUnitType
                         << " (" << H264NalUnitTypeName(nalUnitType) << ')'
                         << " nal_ref_idc=" << nalRefIdc;
                    nalDetails.push_back(line.str());
                }
            }

            currentStart = nextStart;
            currentStartCodeLength = nextStartCodeLength;
            hasCurrent = hasNext;
        }

        std::string accessUnitError;
        int accessUnitCount = CountH264AccessUnits(fileBytes, accessUnitError);

        std::ostringstream oss;
        oss << "H264 Stream Analysis\n"
            << "====================\n\n";

        oss << "summary:\n"
            << "  file_path: " << filePath << '\n'
            << "  file_size_bytes: " << fileBytes.size() << '\n'
            << "  codec_id: " << avcodec_get_name(codecParameters->codec_id) << '\n'
            << "  width: " << codecParameters->width << '\n'
            << "  height: " << codecParameters->height << '\n'
            << "  profile: " << H264ProfileToString(codecParameters->profile) << '\n'
            << "  level: " << H264LevelToString(codecParameters->level) << '\n'
            << "  nal_unit_count: " << nalUnitCount << '\n'
            << "  sps_count: " << spsCount << '\n'
            << "  pps_count: " << ppsCount << '\n'
            << "  sei_count: " << seiCount << '\n'
            << "  aud_count: " << audCount << '\n'
            << "  idr_count: " << idrCount << '\n'
            << "  non_idr_count: " << nonIdrCount << '\n';
        if (accessUnitCount >= 0) {
            oss << "  access_unit_count: " << accessUnitCount << '\n';
        } else {
            oss << "  access_unit_count: unknown\n";
        }

        oss << "\nstream_info:\n"
            << "  stream_index: " << videoStreamIndex << '\n'
            << "  time_base: " << RationalToString(videoStream->time_base) << '\n'
            << "  avg_frame_rate: " << RationalToString(videoStream->avg_frame_rate) << '\n'
            << "  r_frame_rate: " << RationalToString(videoStream->r_frame_rate) << '\n'
            << "  extradata_size: " << codecParameters->extradata_size << '\n'
            << "  bit_rate: " << codecParameters->bit_rate << '\n';

        if (!accessUnitError.empty()) {
            oss << "  parser_note: " << accessUnitError << '\n';
        }

        oss << "\nnal_type_counts:\n";
        for (const auto &entry : nalTypeCounts) {
            oss << "  type " << entry.first
                << " (" << H264NalUnitTypeName(entry.first) << "): "
                << entry.second << '\n';
        }

        oss << "\nnal_units:\n";
        for (const auto &detail : nalDetails) {
            oss << detail << '\n';
        }
        if (nalUnitCount > static_cast<int>(nalDetails.size())) {
            oss << "  ... truncated, showing first " << nalDetails.size()
                << " of " << nalUnitCount << " NAL units.\n";
        }

        avformat_close_input(&formatContext);
        return oss.str();
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

    void ReleaseSoftAacEncoderResources(SoftAacEncoder *encoder, bool removeOutputFile) {
        if (encoder == nullptr) {
            return;
        }
        if (encoder->audioFifo != nullptr) {
            av_audio_fifo_free(encoder->audioFifo);
            encoder->audioFifo = nullptr;
        }
        if (encoder->swrContext != nullptr) {
            swr_free(&encoder->swrContext);
        }
        if (encoder->codecContext != nullptr) {
            avcodec_free_context(&encoder->codecContext);
        }
        if (encoder->outputContext != nullptr) {
            if (!(encoder->outputContext->oformat->flags & AVFMT_NOFILE) &&
                encoder->outputContext->pb != nullptr) {
                avio_closep(&encoder->outputContext->pb);
            }
            avformat_free_context(encoder->outputContext);
            encoder->outputContext = nullptr;
        }
        av_channel_layout_uninit(&encoder->inputChannelLayout);
        if (removeOutputFile && !encoder->outputPath.empty()) {
            std::remove(encoder->outputPath.c_str());
        }
    }

    void ReleaseSoftVideoEncoderResources(SoftVideoEncoder *encoder, bool removeOutputFile) {
        if (encoder == nullptr) {
            return;
        }
        if (encoder->outputFile != nullptr) {
            std::fclose(encoder->outputFile);
            encoder->outputFile = nullptr;
        }
        if (encoder->codecContext != nullptr) {
            avcodec_free_context(&encoder->codecContext);
        }
        if (removeOutputFile && !encoder->outputPath.empty()) {
            std::remove(encoder->outputPath.c_str());
        }
    }

    int ReceiveSoftVideoPackets(SoftVideoEncoder *encoder) {
        if (encoder == nullptr || encoder->codecContext == nullptr || encoder->outputFile == nullptr) {
            return AVERROR(EINVAL);
        }

        int emittedPackets = 0;
        AVPacket *packet = av_packet_alloc();
        if (packet == nullptr) {
            return AVERROR(ENOMEM);
        }

        while (true) {
            int result = avcodec_receive_packet(encoder->codecContext, packet);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                av_packet_free(&packet);
                return emittedPackets;
            }
            if (result < 0) {
                av_packet_free(&packet);
                return result;
            }

            if (std::fwrite(packet->data, 1, static_cast<size_t>(packet->size), encoder->outputFile) !=
                static_cast<size_t>(packet->size)) {
                av_packet_unref(packet);
                av_packet_free(&packet);
                return AVERROR(EIO);
            }

            av_packet_unref(packet);
            encoder->packetCount += 1;
            emittedPackets += 1;
        }
    }

    std::string NormalizeX264Profile(const std::string &profile) {
        std::string normalized = profile;
        std::transform(
            normalized.begin(),
            normalized.end(),
            normalized.begin(),
            [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); }
        );
        if (normalized == "baseline" || normalized == "main" || normalized == "high") {
            return normalized;
        }
        return "baseline";
    }

    std::string OpenSoftVideoEncoder(
        SoftVideoEncoder *encoder,
        const std::string &outputPath,
        int width,
        int height,
        int frameRate,
        int bitrate,
        const std::string &profile,
        int iFrameInterval
    ) {
        if (encoder == nullptr) {
            return "ERROR: soft video encoder session is null.";
        }

        const AVCodec *codec = avcodec_find_encoder_by_name("libx264");
        if (codec == nullptr) {
            return "ERROR: FFmpeg libx264 encoder is not available in this build.";
        }

        encoder->outputPath = outputPath;
        encoder->width = std::max(width, 1);
        encoder->height = std::max(height, 1);
        encoder->frameRate = std::max(frameRate, 1);
        encoder->bitrate = std::max(bitrate, 1);
        encoder->iFrameInterval = std::max(iFrameInterval, 1);

        encoder->codecContext = avcodec_alloc_context3(codec);
        if (encoder->codecContext == nullptr) {
            return "ERROR: failed to allocate libx264 codec context.";
        }

        encoder->codecContext->codec_type = AVMEDIA_TYPE_VIDEO;
        encoder->codecContext->codec_id = AV_CODEC_ID_H264;
        encoder->codecContext->width = encoder->width;
        encoder->codecContext->height = encoder->height;
        encoder->codecContext->pix_fmt = AV_PIX_FMT_YUV420P;
        encoder->codecContext->bit_rate = encoder->bitrate;
        encoder->codecContext->framerate = AVRational{encoder->frameRate, 1};
        encoder->codecContext->time_base = AVRational{1, encoder->frameRate};
        encoder->codecContext->gop_size = encoder->frameRate * encoder->iFrameInterval;
        encoder->codecContext->max_b_frames = 0;
        encoder->codecContext->thread_count = 1;

        av_opt_set(encoder->codecContext->priv_data, "preset", "veryfast", 0);
        av_opt_set(encoder->codecContext->priv_data, "tune", "zerolatency", 0);
        av_opt_set(
            encoder->codecContext->priv_data,
            "x264-params",
            "repeat-headers=1:annexb=1",
            0
        );
        av_opt_set(
            encoder->codecContext->priv_data,
            "profile",
            NormalizeX264Profile(profile).c_str(),
            0
        );

        int result = avcodec_open2(encoder->codecContext, codec, nullptr);
        if (result < 0) {
            ReleaseSoftVideoEncoderResources(encoder, true);
            return "ERROR: failed to open libx264 encoder: " + AvErrorToString(result);
        }

        encoder->outputFile = std::fopen(outputPath.c_str(), "wb");
        if (encoder->outputFile == nullptr) {
            ReleaseSoftVideoEncoderResources(encoder, true);
            return "ERROR: failed to open H.264 output file.";
        }

        encoder->frameCount = 0;
        encoder->packetCount = 0;
        encoder->lastFramePts = AV_NOPTS_VALUE;
        return "OK: FFmpeg libx264 soft video encoder started.";
    }

    int WriteSoftVideoFrame(
        SoftVideoEncoder *encoder,
        const uint8_t *data,
        int size,
        int width,
        int height,
        int64_t ptsUs
    ) {
        if (encoder == nullptr || encoder->codecContext == nullptr || encoder->outputFile == nullptr ||
            data == nullptr || size <= 0) {
            return AVERROR(EINVAL);
        }
        if (width != encoder->width || height != encoder->height) {
            return AVERROR(EINVAL);
        }

        const int yPlaneSize = width * height;
        const int uvPlaneSize = yPlaneSize / 4;
        const int expectedSize = yPlaneSize + uvPlaneSize * 2;
        if (size < expectedSize) {
            return AVERROR(EINVAL);
        }

        AVFrame *frame = av_frame_alloc();
        if (frame == nullptr) {
            return AVERROR(ENOMEM);
        }

        frame->format = encoder->codecContext->pix_fmt;
        frame->width = encoder->width;
        frame->height = encoder->height;

        int result = av_frame_get_buffer(frame, 32);
        if (result < 0) {
            av_frame_free(&frame);
            return result;
        }

        result = av_frame_make_writable(frame);
        if (result < 0) {
            av_frame_free(&frame);
            return result;
        }

        const uint8_t *ySource = data;
        const uint8_t *uSource = data + yPlaneSize;
        const uint8_t *vSource = data + yPlaneSize + uvPlaneSize;

        for (int row = 0; row < height; ++row) {
            std::memcpy(
                frame->data[0] + row * frame->linesize[0],
                ySource + row * width,
                static_cast<size_t>(width)
            );
        }
        for (int row = 0; row < height / 2; ++row) {
            std::memcpy(
                frame->data[1] + row * frame->linesize[1],
                uSource + row * (width / 2),
                static_cast<size_t>(width / 2)
            );
            std::memcpy(
                frame->data[2] + row * frame->linesize[2],
                vSource + row * (width / 2),
                static_cast<size_t>(width / 2)
            );
        }

        int64_t nextPts =
            ptsUs > 0
                ? av_rescale_q(ptsUs, AVRational{1, 1000000}, encoder->codecContext->time_base)
                : encoder->frameCount;
        if (encoder->lastFramePts != AV_NOPTS_VALUE && nextPts <= encoder->lastFramePts) {
            nextPts = encoder->lastFramePts + 1;
        }
        frame->pts = nextPts;
        encoder->lastFramePts = nextPts;

        result = avcodec_send_frame(encoder->codecContext, frame);
        av_frame_free(&frame);
        if (result < 0) {
            return result;
        }

        result = ReceiveSoftVideoPackets(encoder);
        if (result < 0) {
            return result;
        }

        encoder->frameCount += 1;
        return result;
    }

    std::string CloseSoftVideoEncoder(std::unique_ptr<SoftVideoEncoder> &encoder) {
        if (encoder == nullptr) {
            return "FFmpeg libx264 soft video encoder was not running.";
        }

        int result = 0;
        if (encoder->codecContext != nullptr) {
            result = avcodec_send_frame(encoder->codecContext, nullptr);
            if (result < 0) {
                std::string message =
                    "ERROR: failed to signal libx264 encoder end of stream: " + AvErrorToString(result);
                ReleaseSoftVideoEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }

            result = ReceiveSoftVideoPackets(encoder.get());
            if (result < 0) {
                std::string message =
                    "ERROR: failed to drain final libx264 packets: " + AvErrorToString(result);
                ReleaseSoftVideoEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }
        }

        if (encoder->outputFile != nullptr) {
            std::fflush(encoder->outputFile);
        }

        std::ostringstream oss;
        oss << "FFmpeg libx264 soft video encode completed.\n"
            << "frames: " << encoder->frameCount << '\n'
            << "packets: " << encoder->packetCount << '\n'
            << "output: " << encoder->outputPath;

        ReleaseSoftVideoEncoderResources(encoder.get(), false);
        encoder.reset();
        return oss.str();
    }

    int ReceiveSoftAacPackets(SoftAacEncoder *encoder) {
        if (encoder == nullptr || encoder->codecContext == nullptr || encoder->outputContext == nullptr) {
            return AVERROR(EINVAL);
        }

        int emittedPackets = 0;
        AVPacket *packet = av_packet_alloc();
        if (packet == nullptr) {
            return AVERROR(ENOMEM);
        }

        while (true) {
            int result = avcodec_receive_packet(encoder->codecContext, packet);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                av_packet_free(&packet);
                return emittedPackets;
            }
            if (result < 0) {
                av_packet_free(&packet);
                return result;
            }

            av_packet_rescale_ts(packet, encoder->codecContext->time_base, encoder->audioStream->time_base);
            packet->stream_index = encoder->audioStream->index;
            packet->pos = -1;
            result = av_interleaved_write_frame(encoder->outputContext, packet);
            av_packet_unref(packet);
            if (result < 0) {
                av_packet_free(&packet);
                return result;
            }

            encoder->packetCount += 1;
            emittedPackets += 1;
        }
    }

    int SendSoftAacFrame(SoftAacEncoder *encoder, AVFrame *frame) {
        if (encoder == nullptr || encoder->codecContext == nullptr) {
            return AVERROR(EINVAL);
        }
        int result = avcodec_send_frame(encoder->codecContext, frame);
        if (result < 0) {
            return result;
        }
        return ReceiveSoftAacPackets(encoder);
    }

    int EncodeSoftAacFromFifo(SoftAacEncoder *encoder, bool flushPartialFrame) {
        if (encoder == nullptr || encoder->codecContext == nullptr || encoder->audioFifo == nullptr) {
            return AVERROR(EINVAL);
        }

        int emittedPackets = 0;
        const int frameSize = encoder->codecContext->frame_size;
        if (frameSize <= 0) {
            return AVERROR(EINVAL);
        }

        while (av_audio_fifo_size(encoder->audioFifo) >= frameSize ||
            (flushPartialFrame && av_audio_fifo_size(encoder->audioFifo) > 0)) {
            const int availableSamples = av_audio_fifo_size(encoder->audioFifo);
            const int samplesToRead = flushPartialFrame ? std::min(availableSamples, frameSize) : frameSize;

            AVFrame *frame = av_frame_alloc();
            if (frame == nullptr) {
                return AVERROR(ENOMEM);
            }
            frame->nb_samples = frameSize;
            frame->format = encoder->codecContext->sample_fmt;
            frame->sample_rate = encoder->codecContext->sample_rate;
            int result = av_channel_layout_copy(&frame->ch_layout, &encoder->codecContext->ch_layout);
            if (result < 0) {
                av_frame_free(&frame);
                return result;
            }

            result = av_frame_get_buffer(frame, 0);
            if (result < 0) {
                av_frame_free(&frame);
                return result;
            }

            result = av_frame_make_writable(frame);
            if (result < 0) {
                av_frame_free(&frame);
                return result;
            }

            if (samplesToRead < frameSize) {
                av_samples_set_silence(
                    frame->data,
                    0,
                    frameSize,
                    encoder->codecContext->ch_layout.nb_channels,
                    encoder->codecContext->sample_fmt
                );
            }

            result = av_audio_fifo_read(encoder->audioFifo, reinterpret_cast<void **>(frame->data), samplesToRead);
            if (result < samplesToRead) {
                av_frame_free(&frame);
                return AVERROR(EIO);
            }

            frame->pts = encoder->nextPts;
            encoder->nextPts += frameSize;

            result = SendSoftAacFrame(encoder, frame);
            av_frame_free(&frame);
            if (result < 0) {
                return result;
            }
            emittedPackets += result;

            if (!flushPartialFrame && av_audio_fifo_size(encoder->audioFifo) < frameSize) {
                break;
            }
        }

        return emittedPackets;
    }

    std::string OpenSoftAacEncoder(
        SoftAacEncoder *encoder,
        const std::string &outputPath,
        int sampleRate,
        int channelCount,
        int bitrate,
        int profile
    ) {
        if (encoder == nullptr) {
            return "ERROR: soft AAC encoder session is null.";
        }

        const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (codec == nullptr) {
            return "ERROR: FFmpeg AAC encoder is not available in this build.";
        }

        encoder->outputPath = outputPath;
        encoder->inputSampleRate = std::max(sampleRate, 1);
        encoder->inputChannelCount = std::max(channelCount, 1);
        encoder->bitrate = std::max(bitrate, 1);

        av_channel_layout_default(&encoder->inputChannelLayout, encoder->inputChannelCount);

        int result = avformat_alloc_output_context2(
            &encoder->outputContext,
            nullptr,
            "adts",
            outputPath.c_str()
        );
        if (result < 0 || encoder->outputContext == nullptr) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to allocate ADTS output context: " +
                AvErrorToString(result < 0 ? result : AVERROR_UNKNOWN);
        }

        encoder->audioStream = avformat_new_stream(encoder->outputContext, nullptr);
        if (encoder->audioStream == nullptr) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to create AAC output stream.";
        }

        encoder->codecContext = avcodec_alloc_context3(codec);
        if (encoder->codecContext == nullptr) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to allocate AAC codec context.";
        }

        encoder->codecContext->codec_type = AVMEDIA_TYPE_AUDIO;
        encoder->codecContext->codec_id = AV_CODEC_ID_AAC;
        encoder->codecContext->bit_rate = encoder->bitrate;
        encoder->codecContext->profile = profile;
        encoder->codecContext->sample_rate = encoder->inputSampleRate;
        encoder->codecContext->time_base = AVRational{1, encoder->inputSampleRate};
        result = av_channel_layout_copy(&encoder->codecContext->ch_layout, &encoder->inputChannelLayout);
        if (result < 0) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to copy AAC channel layout: " + AvErrorToString(result);
        }
        encoder->codecContext->sample_fmt =
            codec->sample_fmts != nullptr ? codec->sample_fmts[0] : AV_SAMPLE_FMT_FLTP;

        result = avcodec_open2(encoder->codecContext, codec, nullptr);
        if (result < 0) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to open AAC encoder: " + AvErrorToString(result);
        }

        encoder->audioStream->time_base = encoder->codecContext->time_base;
        result = avcodec_parameters_from_context(encoder->audioStream->codecpar, encoder->codecContext);
        if (result < 0) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to copy AAC codec parameters: " + AvErrorToString(result);
        }

        result = swr_alloc_set_opts2(
            &encoder->swrContext,
            &encoder->codecContext->ch_layout,
            encoder->codecContext->sample_fmt,
            encoder->codecContext->sample_rate,
            &encoder->inputChannelLayout,
            AV_SAMPLE_FMT_S16,
            encoder->inputSampleRate,
            0,
            nullptr
        );
        if (result < 0 || encoder->swrContext == nullptr) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to configure AAC resampler: " +
                AvErrorToString(result < 0 ? result : AVERROR_UNKNOWN);
        }

        result = swr_init(encoder->swrContext);
        if (result < 0) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to initialize AAC resampler: " + AvErrorToString(result);
        }

        encoder->audioFifo = av_audio_fifo_alloc(
            encoder->codecContext->sample_fmt,
            encoder->codecContext->ch_layout.nb_channels,
            std::max(encoder->codecContext->frame_size * 4, 4096)
        );
        if (encoder->audioFifo == nullptr) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to allocate AAC audio fifo.";
        }

        if (!(encoder->outputContext->oformat->flags & AVFMT_NOFILE)) {
            result = avio_open(&encoder->outputContext->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
            if (result < 0) {
                ReleaseSoftAacEncoderResources(encoder, true);
                return "ERROR: failed to open AAC output file: " + AvErrorToString(result);
            }
        }

        result = avformat_write_header(encoder->outputContext, nullptr);
        if (result < 0) {
            ReleaseSoftAacEncoderResources(encoder, true);
            return "ERROR: failed to write AAC header: " + AvErrorToString(result);
        }

        encoder->headerWritten = true;
        encoder->packetCount = 0;
        encoder->nextPts = 0;
        return "OK: FFmpeg soft AAC encoder started.";
    }

    int WriteSoftAacPcm(
        SoftAacEncoder *encoder,
        const uint8_t *data,
        int size
    ) {
        if (encoder == nullptr || encoder->codecContext == nullptr || encoder->swrContext == nullptr ||
            encoder->audioFifo == nullptr || size <= 0 || data == nullptr) {
            return AVERROR(EINVAL);
        }

        const int inputBytesPerSample = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        const int inputFrameBytes = inputBytesPerSample * std::max(encoder->inputChannelCount, 1);
        if (inputFrameBytes <= 0) {
            return AVERROR(EINVAL);
        }

        const int alignedSize = size - (size % inputFrameBytes);
        if (alignedSize <= 0) {
            return 0;
        }

        const int inputSamples = alignedSize / inputFrameBytes;
        const uint8_t *inputData[1] = {data};
        const int maxOutputSamples = av_rescale_rnd(
            swr_get_delay(encoder->swrContext, encoder->inputSampleRate) + inputSamples,
            encoder->codecContext->sample_rate,
            encoder->inputSampleRate,
            AV_ROUND_UP
        );

        uint8_t **convertedData = nullptr;
        int result = av_samples_alloc_array_and_samples(
            &convertedData,
            nullptr,
            encoder->codecContext->ch_layout.nb_channels,
            maxOutputSamples,
            encoder->codecContext->sample_fmt,
            0
        );
        if (result < 0) {
            return result;
        }

        const int convertedSamples = swr_convert(
            encoder->swrContext,
            convertedData,
            maxOutputSamples,
            inputData,
            inputSamples
        );
        if (convertedSamples < 0) {
            if (convertedData != nullptr) {
                av_freep(&convertedData[0]);
            }
            av_freep(&convertedData);
            return convertedSamples;
        }

        result = av_audio_fifo_realloc(
            encoder->audioFifo,
            av_audio_fifo_size(encoder->audioFifo) + convertedSamples
        );
        if (result < 0) {
            if (convertedData != nullptr) {
                av_freep(&convertedData[0]);
            }
            av_freep(&convertedData);
            return result;
        }

        result = av_audio_fifo_write(
            encoder->audioFifo,
            reinterpret_cast<void **>(convertedData),
            convertedSamples
        );
        if (convertedData != nullptr) {
            av_freep(&convertedData[0]);
        }
        av_freep(&convertedData);
        if (result < convertedSamples) {
            return AVERROR(EIO);
        }

        return EncodeSoftAacFromFifo(encoder, false);
    }

    std::string CloseSoftAacEncoder(std::unique_ptr<SoftAacEncoder> &encoder) {
        if (encoder == nullptr) {
            return "FFmpeg soft AAC encoder was not running.";
        }

        int result = 0;
        if (encoder->codecContext != nullptr && encoder->audioFifo != nullptr) {
            result = EncodeSoftAacFromFifo(encoder.get(), true);
            if (result < 0) {
                std::string message = "ERROR: failed to flush remaining AAC samples: " + AvErrorToString(result);
                ReleaseSoftAacEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }

            result = avcodec_send_frame(encoder->codecContext, nullptr);
            if (result < 0) {
                std::string message = "ERROR: failed to signal AAC encoder end of stream: " + AvErrorToString(result);
                ReleaseSoftAacEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }

            result = ReceiveSoftAacPackets(encoder.get());
            if (result < 0) {
                std::string message = "ERROR: failed to drain final AAC packets: " + AvErrorToString(result);
                ReleaseSoftAacEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }
        }

        if (encoder->headerWritten && encoder->outputContext != nullptr) {
            result = av_write_trailer(encoder->outputContext);
            if (result < 0) {
                std::string message = "ERROR: failed to finalize AAC output file: " + AvErrorToString(result);
                ReleaseSoftAacEncoderResources(encoder.get(), true);
                encoder.reset();
                return message;
            }
        }

        std::ostringstream oss;
        oss << "FFmpeg soft AAC encode completed.\n"
            << "packets: " << encoder->packetCount << '\n'
            << "output: " << encoder->outputPath;

        ReleaseSoftAacEncoderResources(encoder.get(), false);
        encoder.reset();
        return oss.str();
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
Java_io_ffmpegtutotial_player_internal_NativeInstance_analyzeH264Stream(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jstring filePath
) {
    if (filePath == nullptr) {
        return env->NewStringUTF("ERROR: file path is null.");
    }

    const char *filePathChars = env->GetStringUTFChars(filePath, nullptr);
    std::string result = AnalyzeH264Stream(filePathChars);
    env->ReleaseStringUTFChars(filePath, filePathChars);
    return env->NewStringUTF(result.c_str());
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

    std::string result = MuxToFlv(videoPathChars, audioPathChars, outputPathChars);

    env->ReleaseStringUTFChars(videoPath, videoPathChars);
    env->ReleaseStringUTFChars(audioPath, audioPathChars);
    env->ReleaseStringUTFChars(outputPath, outputPathChars);

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
        holder->liveMuxer.reset();
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
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    std::lock_guard<std::mutex> lock(holder->liveMuxerMutex);
    std::string result = CloseLiveFlvMuxer(holder->liveMuxer);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_openSoftAacEncoder(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jstring outputPath,
    jint sampleRate,
    jint channelCount,
    jint bitrate,
    jint profile
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    const char *outputPathChars = env->GetStringUTFChars(outputPath, nullptr);
    std::lock_guard<std::mutex> lock(holder->softAacEncoderMutex);
    if (holder->softAacEncoder != nullptr) {
        CloseSoftAacEncoder(holder->softAacEncoder);
    }
    holder->softAacEncoder = std::make_unique<SoftAacEncoder>();
    std::string result = OpenSoftAacEncoder(
        holder->softAacEncoder.get(),
        outputPathChars,
        sampleRate,
        channelCount,
        bitrate,
        profile
    );
    if (result.rfind("OK:", 0) != 0) {
        holder->softAacEncoder.reset();
    }

    env->ReleaseStringUTFChars(outputPath, outputPathChars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_openSoftVideoEncoder(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jstring outputPath,
    jint width,
    jint height,
    jint frameRate,
    jint bitrate,
    jstring profile,
    jint iFrameInterval
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    const char *outputPathChars = env->GetStringUTFChars(outputPath, nullptr);
    const char *profileChars = profile != nullptr ? env->GetStringUTFChars(profile, nullptr) : nullptr;

    std::lock_guard<std::mutex> lock(holder->softVideoEncoderMutex);
    if (holder->softVideoEncoder != nullptr) {
        CloseSoftVideoEncoder(holder->softVideoEncoder);
    }
    holder->softVideoEncoder = std::make_unique<SoftVideoEncoder>();
    std::string result = OpenSoftVideoEncoder(
        holder->softVideoEncoder.get(),
        outputPathChars,
        width,
        height,
        frameRate,
        bitrate,
        profileChars != nullptr ? profileChars : "baseline",
        iFrameInterval
    );
    if (result.rfind("OK:", 0) != 0) {
        holder->softVideoEncoder.reset();
    }

    if (profileChars != nullptr) {
        env->ReleaseStringUTFChars(profile, profileChars);
    }
    env->ReleaseStringUTFChars(outputPath, outputPathChars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_writeSoftVideoFrame(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jbyteArray i420Data,
    jint width,
    jint height,
    jlong ptsUs
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return AVERROR(EINVAL);
    }

    std::vector<uint8_t> frameBytes = JByteArrayToVector(env, i420Data);
    std::lock_guard<std::mutex> lock(holder->softVideoEncoderMutex);
    return WriteSoftVideoFrame(
        holder->softVideoEncoder.get(),
        frameBytes.data(),
        static_cast<int>(frameBytes.size()),
        width,
        height,
        ptsUs
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_closeSoftVideoEncoder(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    std::lock_guard<std::mutex> lock(holder->softVideoEncoderMutex);
    std::string result = CloseSoftVideoEncoder(holder->softVideoEncoder);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_writeSoftAacPcm(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle,
    jbyteArray pcmData,
    jint size
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return AVERROR(EINVAL);
    }

    std::vector<uint8_t> pcmBytes = JByteArrayToVector(env, pcmData);
    const int safeSize = std::min(static_cast<int>(pcmBytes.size()), static_cast<int>(size));
    std::lock_guard<std::mutex> lock(holder->softAacEncoderMutex);
    return WriteSoftAacPcm(
        holder->softAacEncoder.get(),
        pcmBytes.data(),
        safeSize
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_closeSoftAacEncoder(
    JNIEnv *env,
    jobject obj,
    jlong nativeHandle
) {
    auto *holder = reinterpret_cast<InstanceHolder *>(nativeHandle);
    if (holder == nullptr) {
        return env->NewStringUTF("ERROR: native instance holder is null.");
    }

    std::lock_guard<std::mutex> lock(holder->softAacEncoderMutex);
    std::string result = CloseSoftAacEncoder(holder->softAacEncoder);
    return env->NewStringUTF(result.c_str());
}




