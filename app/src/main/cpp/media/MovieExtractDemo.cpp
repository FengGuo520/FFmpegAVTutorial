#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/adts_parser.h"
#include "libavcodec/avcodec.h"
#include "libavcodec/bsf.h"
#include "libavcodec/codec_desc.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
}

namespace {

constexpr int kAdtsHeaderSize = AV_AAC_ADTS_HEADER_SIZE;

std::string ErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

// 根据 codec id 取一个更适合展示和拼接文件名的编码名称。
// 优先走 AVCodecDescriptor，是因为它是 FFmpeg 的编解码描述表，
// 通常能稳定拿到像 aac / h264 / hevc 这样的短名字。
// 如果 descriptor 为空，再退回 avcodec_get_name，保证未知或特殊 codec
// 也尽量能返回一个可读名字，而不是直接丢失信息。
std::string CodecLabel(AVCodecID codecId) {
    const AVCodecDescriptor *descriptor = avcodec_descriptor_get(codecId);
    if (descriptor != nullptr && descriptor->name != nullptr) {
        return descriptor->name;
    }
    return avcodec_get_name(codecId);
}

std::string DescribeBsfCodecIds(const AVCodecID *codecIds) {
    if (codecIds == nullptr) {
        return "any";
    }

    std::ostringstream oss;
    bool first = true;
    for (int index = 0; codecIds[index] != AV_CODEC_ID_NONE; ++index) {
        if (!first) {
            oss << ", ";
        }
        first = false;
        oss << CodecLabel(codecIds[index]) << "(" << codecIds[index] << ")";
    }
    if (first) {
        return "none";
    }
    return oss.str();
}

std::string SanitizeFileStem(const std::string &text) {
    std::string result;
    result.reserve(text.size());
    for (unsigned char c : text) {
        if (std::isalnum(c) || c == '_' || c == '-' || c == '.') {
            result.push_back(static_cast<char>(c));
        } else {
            result.push_back('_');
        }
    }
    if (result.empty()) {
        return "media";
    }
    return result;
}

std::string FileNameFromPath(const std::string &path) {
    const size_t slash = path.find_last_of("/\\");
    if (slash == std::string::npos) {
        return path;
    }
    return path.substr(slash + 1);
}

std::string FileStemFromPath(const std::string &path) {
    std::string name = FileNameFromPath(path);
    const size_t dot = name.find_last_of('.');
    if (dot == std::string::npos) {
        return SanitizeFileStem(name);
    }
    return SanitizeFileStem(name.substr(0, dot));
}

const char *OutputExtension(AVCodecID codecId) {
    switch (codecId) {
        case AV_CODEC_ID_AAC:
            return "aac";
        case AV_CODEC_ID_H264:
            return "h264";
        case AV_CODEC_ID_HEVC:
            return "h265";
        default:
            return "bin";
    }
}

bool IsSupportedCodec(AVCodecID codecId) {
    return codecId == AV_CODEC_ID_AAC
        || codecId == AV_CODEC_ID_H264
        || codecId == AV_CODEC_ID_HEVC;
}

// 判断当前视频流在提取时，是否需要先经过 mp4toannexb 这类 bitstream filter。
// 这里主要针对 H264 / H265：
// - 如果流来自 MP4 / FLV 这类容器，包内通常是长度前缀格式，extradata 常以 0x01 开头
//   表示 AVCDecoderConfigurationRecord / HEVCDecoderConfigurationRecord。
// - 而我们想落地成 .h264 / .h265 裸流时，更希望输出 Annex B 风格，
//   也就是每个 NAL 前面带 00 00 00 01 起始码。
// 所以这个函数本质上是在判断：当前包数据是不是“更像容器内格式”，
// 如果是，就在后面挂上 bsf 把它转成 Annex B 再写文件。
bool NeedsAnnexBFilter(const AVCodecParameters *parameters) {
    if (parameters == nullptr) {
        return false;
    }
    if (parameters->codec_id != AV_CODEC_ID_H264 && parameters->codec_id != AV_CODEC_ID_HEVC) {
        return false;
    }
    return parameters->extradata != nullptr
        && parameters->extradata_size > 0
        && parameters->extradata[0] == 1;
}

int AacSampleRateIndex(int sampleRate) {
    static const int kSampleRates[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    };
    for (int i = 0; i < static_cast<int>(std::size(kSampleRates)); ++i) {
        if (kSampleRates[i] == sampleRate) {
            return i;
        }
    }
    return 4;
}

int ParseAacObjectType(const AVCodecParameters *parameters) {
    if (parameters == nullptr || parameters->extradata == nullptr || parameters->extradata_size < 2) {
        return 2;
    }
    const uint8_t *extradata = parameters->extradata;
    int audioObjectType = (extradata[0] >> 3) & 0x1F;
    if (audioObjectType == 31 && parameters->extradata_size >= 3) {
        audioObjectType = 32 + ((extradata[0] & 0x07) << 3) + ((extradata[1] >> 5) & 0x07);
    }
    if (audioObjectType <= 0) {
        return 2;
    }
    return audioObjectType;
}

int ToAdtsProfile(int audioObjectType) {
    switch (audioObjectType) {
        case 1:
            return 0;
        case 2:
            return 1;
        case 3:
            return 2;
        case 4:
            return 3;
        default:
            return 1;
    }
}

std::string FormatBytes(int64_t bytes) {
    std::ostringstream oss;
    if (bytes >= 1024 * 1024) {
        oss << std::fixed << std::setprecision(2)
            << static_cast<double>(bytes) / (1024.0 * 1024.0) << " MB";
    } else if (bytes >= 1024) {
        oss << std::fixed << std::setprecision(2)
            << static_cast<double>(bytes) / 1024.0 << " KB";
    } else {
        oss << bytes << " B";
    }
    oss << " (" << bytes << " bytes)";
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

struct StreamExtractor {
    int streamIndex = -1;
    AVCodecID codecId = AV_CODEC_ID_NONE;
    std::string codecLabel;
    std::string outputPath;
    std::ofstream output;
    AVBSFContext *bsf = nullptr;
    bool needsAdts = false;
    int adtsProfile = 1;
    int sampleRateIndex = 4;
    int channelConfig = 2;
    int64_t packetCount = 0;
    int64_t bytesWritten = 0;
};

bool WriteBinary(StreamExtractor &extractor, const uint8_t *data, size_t size) {
    if (data == nullptr || size == 0) {
        return true;
    }
    extractor.output.write(reinterpret_cast<const char *>(data), static_cast<std::streamsize>(size));
    if (!extractor.output.good()) {
        return false;
    }
    extractor.bytesWritten += static_cast<int64_t>(size);
    return true;
}

bool WriteAdtsHeader(StreamExtractor &extractor, int payloadSize) {
    const int frameLength = kAdtsHeaderSize + payloadSize;
    if (frameLength > 0x1FFF) {
        return false;
    }

    uint8_t header[kAdtsHeaderSize] = {0};
    header[0] = 0xFF;
    header[1] = 0xF1;
    header[2] = static_cast<uint8_t>(((extractor.adtsProfile & 0x03) << 6)
        | ((extractor.sampleRateIndex & 0x0F) << 2)
        | ((extractor.channelConfig >> 2) & 0x01));
    header[3] = static_cast<uint8_t>(((extractor.channelConfig & 0x03) << 6)
        | ((frameLength >> 11) & 0x03));
    header[4] = static_cast<uint8_t>((frameLength >> 3) & 0xFF);
    header[5] = static_cast<uint8_t>(((frameLength & 0x07) << 5) | 0x1F);
    header[6] = 0xFC;
    return WriteBinary(extractor, header, sizeof(header));
}

// 把一个已经准备好的压缩包真正写到目标文件里。
// 这里的“准备好”分两种情况：
// 1. AAC：packet 里通常只有原始 AAC 负载，因此写文件前要先补 ADTS 头
// 2. H264/H265：如果前面挂过 bsf，那么这里拿到的 packet 已经是 Annex B，可直接写
//
// 所以这个函数可以理解成“单包落盘入口”：
// - 先决定是否需要补头
// - 再把 packet->data 写入输出文件
// - 成功后顺手把 packet 计数加一
int WritePacket(StreamExtractor &extractor, AVPacket *packet) {
    if (extractor.needsAdts) {
        // AAC 提取成独立 .aac 文件时，需要让每帧前面都带上 ADTS 头，
        // 这样播放器或分析工具才能把它当成完整的 AAC elementary stream 识别。
        if (!WriteAdtsHeader(extractor, packet->size)) {
            return AVERROR(EINVAL);
        }
    }
    // 无论是 AAC payload 还是已经转好的 Annex B NAL 数据，
    // 最终都是把 packet 当前这块压缩数据直接顺序写入文件。
//    NATIVE_LOGI_TAG(
//            "MovieExtractDemo",
//            "WritePacket codecLabel=%s outputPath=%s",
//            extractor.codecLabel.c_str(),
//            extractor.outputPath.c_str()
//    );
    if (!WriteBinary(extractor, packet->data, static_cast<size_t>(packet->size))) {
        return AVERROR(EIO);
    }
    // packetCount 统计的是“成功写入文件的压缩包个数”。
    extractor.packetCount++;
    return 0;
}

int DrainBsfPackets(StreamExtractor &extractor, AVPacket *packet) {
    while (true) {
        const int result = av_bsf_receive_packet(extractor.bsf, packet);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
            return 0;
        }
        if (result < 0) {
            return result;
        }
        const int writeResult = WritePacket(extractor, packet);
        av_packet_unref(packet);
        if (writeResult < 0) {
            return writeResult;
        }
    }
}

// 为当前视频流创建并初始化 bitstream filter 上下文。
// 这里并不做解码，而是做“压缩包格式重写”：
// - H264 走 h264_mp4toannexb
// - H265 走 hevc_mp4toannexb
// 它们的作用是把 MP4/FLV 这类容器里常见的长度前缀 NAL 包，
// 转成更适合裸流落盘的 Annex B 格式。
//
// 初始化流程分三步：
// 1. av_bsf_alloc：先拿到一个空的 AVBSFContext
// 2. avcodec_parameters_copy：把输入流的 codecpar 拷进去，告诉 bsf 这路流是什么格式
// 3. av_bsf_init：真正完成初始化，后续才能 send/receive packet
int OpenBsf(StreamExtractor &extractor, const AVStream *stream) {
    const char *filterName = extractor.codecId == AV_CODEC_ID_H264
        ? "h264_mp4toannexb"
        : "hevc_mp4toannexb";
    const AVBitStreamFilter *filter = av_bsf_get_by_name(filterName);
    NATIVE_LOGI_TAG(
        "MovieExtractDemo",
        "OpenBsf lookup filterName=%s filter=%p name=%s codecIds=%s privClass=%p inputCodec=%s(%d) streamIndex=%d",
        filterName,
        filter,
        filter != nullptr && filter->name != nullptr ? filter->name : "null",
        filter != nullptr ? DescribeBsfCodecIds(filter->codec_ids).c_str() : "null",
        filter != nullptr ? filter->priv_class : nullptr,
        CodecLabel(extractor.codecId).c_str(),
        extractor.codecId,
        stream != nullptr ? stream->index : -1
    );
    if (filter == nullptr) {
        return AVERROR_BSF_NOT_FOUND;
    }

    int result = av_bsf_alloc(filter, &extractor.bsf);
    if (result < 0) {
        return result;
    }

    result = avcodec_parameters_copy(extractor.bsf->par_in, stream->codecpar);
    if (result < 0) {
        av_bsf_free(&extractor.bsf);
        return result;
    }
    extractor.bsf->time_base_in = stream->time_base;

    result = av_bsf_init(extractor.bsf);
    if (result < 0) {
        av_bsf_free(&extractor.bsf);
        return result;
    }
    return 0;
}

// 为一条目标 AVStream 做提取前准备。
// 这个函数还没开始真正读 packet，它做的是“开工前配置”：
// 1. 记录 streamIndex / codecId / codecLabel，后面用于路由与结果展示
// 2. 生成输出文件名并打开输出文件
// 3. 如果是 AAC，提前准备好 ADTS 头需要的 profile / sampleRateIndex / channelConfig
// 4. 如果是 H264/H265 且当前流属于 MP4 风格，就挂上 bsf，后面写出前转成 Annex B
//
// 可以把它理解成：每发现一条支持提取的流，就先生成一个 StreamExtractor，
// 把这条流后续写文件所需的上下文一次性准备好。
int PrepareExtractor(
    StreamExtractor &extractor,
    const AVStream *stream,
    const std::string &inputStem,
    const std::string &outputDir
) {
    // 先把“这是谁”记录下来：属于哪条流、是什么 codec、展示名称是什么。
    extractor.streamIndex = stream->index;
    extractor.codecId = stream->codecpar->codec_id;
    extractor.codecLabel = CodecLabel(extractor.codecId);

    // 输出文件名统一按“输入文件名 + stream 索引 + codec 名”拼接，
    // 方便后面回看时，一眼知道这是从哪个源文件、哪条流提出来的。
    std::ostringstream outputName;
    outputName << outputDir
               << "/"
               << inputStem
               << "_stream"
               << stream->index
               << "_"
               << extractor.codecLabel
               << "."
               << OutputExtension(extractor.codecId);
    extractor.outputPath = outputName.str();

    // 先把输出文件打开；后续 packet 到来时就可以直接连续写入。
    extractor.output.open(extractor.outputPath, std::ios::binary | std::ios::trunc);
    if (!extractor.output.is_open()) {
        return AVERROR(EIO);
    }

    if (extractor.codecId == AV_CODEC_ID_AAC) {
        // AAC 从 MP4/FLV 这类容器里拆出来时，packet 里通常只有原始 AAC 负载，
        // 不带独立 .aac 文件常见的 ADTS 头。
        // 所以这里先把 ADTS 所需参数准备好，后面每写一帧前都补一个 7 字节头。
        extractor.needsAdts = true;
        extractor.adtsProfile = ToAdtsProfile(ParseAacObjectType(stream->codecpar));
        extractor.sampleRateIndex = AacSampleRateIndex(stream->codecpar->sample_rate);
        extractor.channelConfig = std::clamp(stream->codecpar->ch_layout.nb_channels, 1, 7);
    } else if (NeedsAnnexBFilter(stream->codecpar)) {
        // 对 H264/H265，如果判断当前流仍是容器内长度前缀格式，
        // 这里就提前挂好 bsf，后面每个 packet 先过 bsf 再落盘。
        const int bsfResult = OpenBsf(extractor, stream);
        if (bsfResult < 0) {
            return bsfResult;
        }
    }

    // 到这里说明这条流已经准备完毕，可以进入正式的 packet 读取与写出阶段。
    return 0;
}

void CloseExtractors(std::unordered_map<int, StreamExtractor> &extractors) {
    for (auto &[_, extractor] : extractors) {
        if (extractor.bsf != nullptr) {
            av_bsf_free(&extractor.bsf);
        }
        if (extractor.output.is_open()) {
            extractor.output.close();
        }
    }
}

std::string ExtractStreams(const char *inputPath, const char *outputDir) {
    AVFormatContext *formatContext = nullptr;
    int result = avformat_open_input(&formatContext, inputPath, nullptr, nullptr);
    if (result < 0) {
        return "ERROR: avformat_open_input failed: " + ErrorToString(result);
    }

    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) {
        std::string error = ErrorToString(result);
        avformat_close_input(&formatContext);
        return "ERROR: avformat_find_stream_info failed: " + error;
    }

    const std::string inputStem = FileStemFromPath(inputPath);
    std::unordered_map<int, StreamExtractor> extractors;
    std::vector<std::string> skippedStreams;
    std::vector<std::string> preparedStreams;

    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        const AVCodecID codecId = stream->codecpar->codec_id;
        if (!IsSupportedCodec(codecId)) {
            std::ostringstream skipped;
            skipped << "stream #" << i
                    << " type=" << av_get_media_type_string(stream->codecpar->codec_type)
                    << " codec=" << CodecLabel(codecId)
                    << " -> skipped";
            skippedStreams.push_back(skipped.str());
            continue;
        }

        StreamExtractor extractor;
        result = PrepareExtractor(extractor, stream, inputStem, outputDir);
        if (result < 0) {
            std::string error = ErrorToString(result);
            CloseExtractors(extractors);
            avformat_close_input(&formatContext);
            return "ERROR: prepare extractor failed for stream #"
                + std::to_string(i)
                + ": "
                + error;
        }

        std::ostringstream prepared;
        prepared << "stream #" << i
                 << " codec=" << extractor.codecLabel
                 << " -> " << extractor.outputPath;
        if (extractor.bsf != nullptr) {
            prepared << " (annex-b bsf)";
        }
        if (extractor.needsAdts) {
            prepared << " (adts)";
        }
        preparedStreams.push_back(prepared.str());
        extractors.emplace(stream->index, std::move(extractor));
    }

    if (extractors.empty()) {
        avformat_close_input(&formatContext);
        std::ostringstream oss;
        oss << "Movie Extract Result\n\n";
        oss << "input: " << inputPath << '\n';
        oss << "output_dir: " << outputDir << '\n';
        oss << "supported codecs: AAC / H264 / H265\n\n";
        oss << "No supported streams were found in the selected file.\n";
        if (!skippedStreams.empty()) {
            oss << '\n' << "Skipped Streams\n";
            for (const std::string &line : skippedStreams) {
                oss << line << '\n';
            }
        }
        return oss.str();
    }

    AVPacket *packet = av_packet_alloc();
    if (packet == nullptr) {
        CloseExtractors(extractors);
        avformat_close_input(&formatContext);
        return "ERROR: av_packet_alloc failed.";
    }

    while ((result = av_read_frame(formatContext, packet)) >= 0) {
        auto it = extractors.find(packet->stream_index);
        if (it == extractors.end()) {
            av_packet_unref(packet);
            continue;
        }

        StreamExtractor &extractor = it->second;
        int writeResult = 0;
        if (extractor.bsf != nullptr) {
            writeResult = av_bsf_send_packet(extractor.bsf, packet);
            if (writeResult >= 0) {
                writeResult = DrainBsfPackets(extractor, packet);
            }
        } else {
            writeResult = WritePacket(extractor, packet);
        }
        av_packet_unref(packet);

        if (writeResult < 0) {
            const std::string error = ErrorToString(writeResult);
            av_packet_free(&packet);
            CloseExtractors(extractors);
            avformat_close_input(&formatContext);
            return "ERROR: packet extraction failed on stream #"
                + std::to_string(extractor.streamIndex)
                + ": "
                + error;
        }
    }

    if (result != AVERROR_EOF) {
        const std::string error = ErrorToString(result);
        av_packet_free(&packet);
        CloseExtractors(extractors);
        avformat_close_input(&formatContext);
        return "ERROR: av_read_frame failed: " + error;
    }

    for (auto &[_, extractor] : extractors) {
        if (extractor.bsf == nullptr) {
            continue;
        }
        result = av_bsf_send_packet(extractor.bsf, nullptr);
        if (result < 0) {
            const std::string error = ErrorToString(result);
            av_packet_free(&packet);
            CloseExtractors(extractors);
            avformat_close_input(&formatContext);
            return "ERROR: bsf flush failed on stream #"
                + std::to_string(extractor.streamIndex)
                + ": "
                + error;
        }
        result = DrainBsfPackets(extractor, packet);
        if (result < 0) {
            const std::string error = ErrorToString(result);
            av_packet_free(&packet);
            CloseExtractors(extractors);
            avformat_close_input(&formatContext);
            return "ERROR: bsf drain failed on stream #"
                + std::to_string(extractor.streamIndex)
                + ": "
                + error;
        }
    }

    av_packet_free(&packet);
    avformat_close_input(&formatContext);

    std::ostringstream oss;
    oss << "Movie Extract Result\n\n";
    oss << "input: " << inputPath << '\n';
    oss << "output_dir: " << outputDir << '\n';
    oss << "supported codecs: AAC / H264 / H265\n\n";

    oss << "Prepared Outputs\n";
    for (const std::string &line : preparedStreams) {
        oss << line << '\n';
    }
    oss << '\n';

    oss << "Extraction Summary\n";
    for (const auto &[_, extractor] : extractors) {
        oss << "stream #" << extractor.streamIndex
            << " codec=" << extractor.codecLabel
            << " packets=" << extractor.packetCount
            << " bytes=" << FormatBytes(extractor.bytesWritten)
            << '\n';
        oss << "output: " << extractor.outputPath << '\n';
        if (extractor.needsAdts) {
            oss << "note: AAC packets were wrapped with ADTS headers for standalone playback.\n";
        } else if (extractor.bsf != nullptr) {
            oss << "note: MP4-style length-prefixed video packets were converted to Annex B.\n";
        }
        oss << '\n';
    }

    if (!skippedStreams.empty()) {
        oss << "Skipped Streams\n";
        for (const std::string &line : skippedStreams) {
            oss << line << '\n';
        }
        oss << '\n';
    }

    CloseExtractors(extractors);
    return oss.str();
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_extractMediaStreams(
    JNIEnv *env,
    jobject /* obj */,
    jlong /* nativeHandle */,
    jstring mediaPath,
    jstring outputDir
) {
    const char *pathChars = env->GetStringUTFChars(mediaPath, nullptr);
    const char *outputChars = env->GetStringUTFChars(outputDir, nullptr);
    NATIVE_LOGI_TAG(
        "MovieExtractDemo",
        "extractMediaStreams input=%s outputDir=%s",
        pathChars,
        outputChars
    );
    std::string result = ExtractStreams(pathChars, outputChars);
    LogLongTextByChunk("MovieExtractDemo", "movie extract result", result);
    env->ReleaseStringUTFChars(outputDir, outputChars);
    env->ReleaseStringUTFChars(mediaPath, pathChars);
    return env->NewStringUTF(result.c_str());
}
