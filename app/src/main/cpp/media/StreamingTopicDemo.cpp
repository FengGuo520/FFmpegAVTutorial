#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <iomanip>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec_desc.h"
#include "libavformat/avformat.h"
#include "libavformat/avio.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/pixdesc.h"
#include "libavutil/rational.h"
#include "libavutil/samplefmt.h"
#include "libavutil/time.h"
}

namespace {

struct SegmentInfo {
    double durationSeconds = -1.0;
    std::string title;
    std::string uri;
};

struct VariantInfo {
    std::map<std::string, std::string> attributes;
    std::string uri;
    std::string resolvedUri;
};

struct MediaPlaylistInfo {
    bool isExtM3u = false;
    bool hasEndList = false;
    bool hasMap = false;
    bool hasIndependentSegments = false;
    std::string playlistType;
    std::string targetDuration;
    std::string version;
    std::string mediaSequence;
    std::string mapUri;
    std::vector<SegmentInfo> segments;
};

struct MasterPlaylistInfo {
    bool isExtM3u = false;
    std::vector<VariantInfo> variants;
    std::vector<std::string> mediaTags;
};

struct StreamProbeMetrics {
    int64_t firstPacketMs = -1;
    std::string firstPacketLine;
};

std::string ErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

std::string Trim(const std::string &value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start])) != 0) {
        start++;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1])) != 0) {
        end--;
    }
    return value.substr(start, end - start);
}

bool StartsWith(const std::string &value, const std::string &prefix) {
    return value.rfind(prefix, 0) == 0;
}

std::vector<std::string> SplitLines(const std::string &text) {
    std::vector<std::string> lines;
    std::stringstream ss(text);
    std::string line;
    while (std::getline(ss, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        lines.push_back(line);
    }
    return lines;
}

std::string StripQuery(const std::string &value) {
    const size_t queryPos = value.find('?');
    return queryPos == std::string::npos ? value : value.substr(0, queryPos);
}

std::string ResolveUrl(const std::string &baseUrl, const std::string &reference) {
    if (reference.empty()) {
        return reference;
    }
    if (StartsWith(reference, "http://") || StartsWith(reference, "https://")) {
        return reference;
    }

    char proto[64] = {0};
    char authorization[256] = {0};
    char hostname[256] = {0};
    char path[1024] = {0};
    int port = -1;
    av_url_split(
        proto,
        sizeof(proto),
        authorization,
        sizeof(authorization),
        hostname,
        sizeof(hostname),
        &port,
        path,
        sizeof(path),
        baseUrl.c_str()
    );

    std::ostringstream root;
    root << proto << "://";
    if (authorization[0] != '\0') {
        root << authorization << '@';
    }
    root << hostname;
    if (port >= 0) {
        root << ':' << port;
    }

    if (!reference.empty() && reference[0] == '/') {
        root << reference;
        return root.str();
    }

    std::string basePath = StripQuery(path);
    const size_t slashPos = basePath.find_last_of('/');
    if (slashPos != std::string::npos) {
        basePath = basePath.substr(0, slashPos + 1);
    } else {
        basePath = "/";
    }
    root << basePath << reference;
    return root.str();
}

std::map<std::string, std::string> ParseAttributeList(const std::string &raw) {
    std::map<std::string, std::string> attributes;
    size_t pos = 0;
    while (pos < raw.size()) {
        while (pos < raw.size() && (raw[pos] == ',' || std::isspace(static_cast<unsigned char>(raw[pos])) != 0)) {
            pos++;
        }
        if (pos >= raw.size()) {
            break;
        }
        const size_t eqPos = raw.find('=', pos);
        if (eqPos == std::string::npos) {
            break;
        }
        const std::string key = Trim(raw.substr(pos, eqPos - pos));
        pos = eqPos + 1;

        std::string value;
        if (pos < raw.size() && raw[pos] == '"') {
            pos++;
            const size_t endQuote = raw.find('"', pos);
            if (endQuote == std::string::npos) {
                value = raw.substr(pos);
                pos = raw.size();
            } else {
                value = raw.substr(pos, endQuote - pos);
                pos = endQuote + 1;
            }
        } else {
            const size_t commaPos = raw.find(',', pos);
            if (commaPos == std::string::npos) {
                value = raw.substr(pos);
                pos = raw.size();
            } else {
                value = raw.substr(pos, commaPos - pos);
                pos = commaPos + 1;
            }
        }

        attributes[key] = Trim(value);
    }
    return attributes;
}

std::string FormatSeconds(double seconds) {
    if (seconds < 0.0) {
        return "unknown";
    }
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(3) << seconds << " s";
    return oss.str();
}

std::string FormatDurationSeconds(int64_t timestamp, AVRational timeBase) {
    if (timestamp == AV_NOPTS_VALUE || timestamp < 0 || timeBase.num == 0 || timeBase.den == 0) {
        return "unknown";
    }
    return FormatSeconds(timestamp * av_q2d(timeBase));
}

std::string FormatDurationUs(int64_t durationUs) {
    if (durationUs == AV_NOPTS_VALUE || durationUs < 0) {
        return "unknown";
    }
    return FormatSeconds(static_cast<double>(durationUs) / AV_TIME_BASE);
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

bool FetchTextFromUrl(const std::string &url, int timeoutMs, std::string &outText, std::string &errorText) {
    AVDictionary *options = nullptr;
    av_dict_set(&options, "rw_timeout", std::to_string(static_cast<long long>(timeoutMs) * 1000).c_str(), 0);
    av_dict_set(&options, "timeout", std::to_string(static_cast<long long>(timeoutMs) * 1000).c_str(), 0);
    av_dict_set(&options, "user_agent", "FFmpegAVTutorial/StreamingTopic", 0);

    AVIOContext *io = nullptr;
    const int result = avio_open2(&io, url.c_str(), AVIO_FLAG_READ, nullptr, &options);
    av_dict_free(&options);
    if (result < 0) {
        errorText = "avio_open2 failed: " + ErrorToString(result);
        return false;
    }

    std::string buffer;
    buffer.reserve(64 * 1024);
    uint8_t temp[4096];
    while (buffer.size() < 512 * 1024) {
        const int readResult = avio_read(io, temp, sizeof(temp));
        if (readResult == AVERROR_EOF || readResult == 0) {
            break;
        }
        if (readResult < 0) {
            errorText = "avio_read failed: " + ErrorToString(readResult);
            avio_closep(&io);
            return false;
        }
        buffer.append(reinterpret_cast<char *>(temp), readResult);
    }

    avio_closep(&io);
    outText = buffer;
    return true;
}

MasterPlaylistInfo ParseMasterPlaylist(const std::string &baseUrl, const std::vector<std::string> &lines) {
    MasterPlaylistInfo info;
    if (!lines.empty() && Trim(lines.front()) == "#EXTM3U") {
        info.isExtM3u = true;
    }

    for (size_t i = 0; i < lines.size(); ++i) {
        const std::string line = Trim(lines[i]);
        if (StartsWith(line, "#EXT-X-MEDIA:")) {
            info.mediaTags.push_back(line.substr(std::string("#EXT-X-MEDIA:").size()));
        }
        if (!StartsWith(line, "#EXT-X-STREAM-INF:")) {
            continue;
        }
        VariantInfo variant;
        variant.attributes = ParseAttributeList(line.substr(std::string("#EXT-X-STREAM-INF:").size()));
        for (size_t j = i + 1; j < lines.size(); ++j) {
            const std::string nextLine = Trim(lines[j]);
            if (nextLine.empty() || nextLine[0] == '#') {
                continue;
            }
            variant.uri = nextLine;
            variant.resolvedUri = ResolveUrl(baseUrl, nextLine);
            i = j;
            break;
        }
        info.variants.push_back(variant);
    }

    return info;
}

MediaPlaylistInfo ParseMediaPlaylist(const std::string &baseUrl, const std::vector<std::string> &lines) {
    MediaPlaylistInfo info;
    if (!lines.empty() && Trim(lines.front()) == "#EXTM3U") {
        info.isExtM3u = true;
    }

    double pendingDuration = -1.0;
    std::string pendingTitle;
    for (const std::string &rawLine : lines) {
        const std::string line = Trim(rawLine);
        if (line.empty()) {
            continue;
        }
        if (StartsWith(line, "#EXT-X-TARGETDURATION:")) {
            info.targetDuration = Trim(line.substr(std::string("#EXT-X-TARGETDURATION:").size()));
            continue;
        }
        if (StartsWith(line, "#EXT-X-VERSION:")) {
            info.version = Trim(line.substr(std::string("#EXT-X-VERSION:").size()));
            continue;
        }
        if (StartsWith(line, "#EXT-X-MEDIA-SEQUENCE:")) {
            info.mediaSequence = Trim(line.substr(std::string("#EXT-X-MEDIA-SEQUENCE:").size()));
            continue;
        }
        if (StartsWith(line, "#EXT-X-PLAYLIST-TYPE:")) {
            info.playlistType = Trim(line.substr(std::string("#EXT-X-PLAYLIST-TYPE:").size()));
            continue;
        }
        if (line == "#EXT-X-ENDLIST") {
            info.hasEndList = true;
            continue;
        }
        if (line == "#EXT-X-INDEPENDENT-SEGMENTS") {
            info.hasIndependentSegments = true;
            continue;
        }
        if (StartsWith(line, "#EXT-X-MAP:")) {
            info.hasMap = true;
            const auto attrs = ParseAttributeList(line.substr(std::string("#EXT-X-MAP:").size()));
            const auto it = attrs.find("URI");
            if (it != attrs.end()) {
                info.mapUri = ResolveUrl(baseUrl, it->second);
            }
            continue;
        }
        if (StartsWith(line, "#EXTINF:")) {
            const std::string extinf = line.substr(std::string("#EXTINF:").size());
            const size_t commaPos = extinf.find(',');
            const std::string durationPart = commaPos == std::string::npos ? extinf : extinf.substr(0, commaPos);
            pendingDuration = std::strtod(durationPart.c_str(), nullptr);
            pendingTitle = commaPos == std::string::npos ? "" : Trim(extinf.substr(commaPos + 1));
            continue;
        }
        if (line[0] == '#') {
            continue;
        }

        SegmentInfo segment;
        segment.durationSeconds = pendingDuration;
        segment.title = pendingTitle;
        segment.uri = ResolveUrl(baseUrl, line);
        info.segments.push_back(segment);
        pendingDuration = -1.0;
        pendingTitle.clear();
    }

    return info;
}

std::string GuessSegmentContainer(const MediaPlaylistInfo &playlist) {
    if (playlist.hasMap) {
        return "fMP4/CMAF-like (EXT-X-MAP present)";
    }
    for (const SegmentInfo &segment : playlist.segments) {
        const std::string uri = StripQuery(segment.uri);
        const size_t dotPos = uri.find_last_of('.');
        if (dotPos == std::string::npos) {
            continue;
        }
        const std::string ext = uri.substr(dotPos + 1);
        if (ext == "ts") {
            return "MPEG-TS segments";
        }
        if (ext == "m4s" || ext == "mp4" || ext == "cmfv" || ext == "cmfa") {
            return "fMP4/CMAF-like segments";
        }
    }
    return playlist.hasMap ? "fMP4/CMAF-like" : "unknown";
}

void AppendMasterPlaylistInfo(std::ostringstream &oss, const std::string &url, const MasterPlaylistInfo &playlist) {
    oss << "Root Playlist Analysis\n";
    oss << "playlist_url: " << url << '\n';
    oss << "playlist_kind: master playlist\n";
    oss << "variant_count: " << playlist.variants.size() << '\n';
    oss << "alt_media_tag_count: " << playlist.mediaTags.size() << '\n';
    oss << "Variant Switching Study\n";
    oss << "note: master playlist only advertises candidate variants; actual adaptive switching happens during playback.\n";
    for (size_t i = 0; i < playlist.variants.size(); ++i) {
        const VariantInfo &variant = playlist.variants[i];
        oss << "variant #" << i << '\n';
        const auto bandwidth = variant.attributes.find("BANDWIDTH");
        const auto averageBandwidth = variant.attributes.find("AVERAGE-BANDWIDTH");
        const auto resolution = variant.attributes.find("RESOLUTION");
        const auto codecs = variant.attributes.find("CODECS");
        if (bandwidth != variant.attributes.end()) {
            oss << "  bandwidth: " << bandwidth->second << " bps\n";
        }
        if (averageBandwidth != variant.attributes.end()) {
            oss << "  average_bandwidth: " << averageBandwidth->second << " bps\n";
        }
        if (resolution != variant.attributes.end()) {
            oss << "  resolution: " << resolution->second << '\n';
        }
        if (codecs != variant.attributes.end()) {
            oss << "  codecs: " << codecs->second << '\n';
        }
        oss << "  uri: " << variant.uri << '\n';
        oss << "  resolved_uri: " << variant.resolvedUri << '\n';
    }
    oss << '\n';
}

void AppendMediaPlaylistInfo(std::ostringstream &oss, const std::string &title, const std::string &url, const MediaPlaylistInfo &playlist) {
    double totalDuration = 0.0;
    int extinfCount = 0;
    for (const SegmentInfo &segment : playlist.segments) {
        if (segment.durationSeconds >= 0.0) {
            totalDuration += segment.durationSeconds;
            extinfCount++;
        }
    }

    oss << title << '\n';
    oss << "playlist_url: " << url << '\n';
    oss << "playlist_kind: media playlist\n";
    oss << "playlist_type_tag: " << (playlist.playlistType.empty() ? "not set" : playlist.playlistType) << '\n';
    oss << "live_or_vod: ";
    if (playlist.hasEndList) {
        oss << (playlist.playlistType == "VOD" ? "vod" : "vod/event (ENDLIST present)") << '\n';
    } else {
        oss << "live-like (ENDLIST absent)\n";
    }
    oss << "EXT-X-TARGETDURATION: " << (playlist.targetDuration.empty() ? "unknown" : playlist.targetDuration) << '\n';
    oss << "EXT-X-VERSION: " << (playlist.version.empty() ? "unknown" : playlist.version) << '\n';
    oss << "EXT-X-MEDIA-SEQUENCE: " << (playlist.mediaSequence.empty() ? "unknown" : playlist.mediaSequence) << '\n';
    oss << "EXT-X-MAP: " << (playlist.mapUri.empty() ? "not present" : playlist.mapUri) << '\n';
    oss << "segment_container_guess: " << GuessSegmentContainer(playlist) << '\n';
    oss << "segment_count: " << playlist.segments.size() << '\n';
    oss << "EXTINF_count: " << extinfCount << '\n';
    oss << "EXTINF_total_duration: " << FormatSeconds(totalDuration) << '\n';
    oss << "independent_segments: " << (playlist.hasIndependentSegments ? "true" : "false") << '\n';
    oss << "Segment Request Behavior Study\n";
    oss << "note: FFmpeg will request the media playlist first, then pull listed segments near the playback start or live edge.\n";
    const size_t previewCount = std::min<size_t>(playlist.segments.size(), 8);
    for (size_t i = 0; i < previewCount; ++i) {
        const SegmentInfo &segment = playlist.segments[i];
        oss << "segment #" << i
            << " duration=" << FormatSeconds(segment.durationSeconds)
            << " uri=" << segment.uri;
        if (!segment.title.empty()) {
            oss << " title=" << segment.title;
        }
        oss << '\n';
    }
    if (playlist.segments.size() > previewCount) {
        oss << "... " << (playlist.segments.size() - previewCount) << " more segments\n";
    }
    oss << '\n';
}

void AppendFormatInfo(std::ostringstream &oss, AVFormatContext *formatContext) {
    const AVInputFormat *inputFormat = formatContext->iformat;
    int64_t ioSize = -1;
    if (formatContext->pb != nullptr) {
        ioSize = avio_size(formatContext->pb);
    }

    oss << "Demux Container\n";
    oss << "format_name: " << (inputFormat && inputFormat->name ? inputFormat->name : "unknown") << '\n';
    oss << "format_long_name: " << (inputFormat && inputFormat->long_name ? inputFormat->long_name : "unknown") << '\n';
    oss << "url: " << (formatContext->url ? formatContext->url : "unknown") << '\n';
    oss << "duration: " << FormatDurationUs(formatContext->duration) << '\n';
    oss << "start_time: " << FormatDurationUs(formatContext->start_time) << '\n';
    oss << "bit_rate: " << FormatBitrate(formatContext->bit_rate) << '\n';
    oss << "stream_count: " << formatContext->nb_streams << '\n';
    oss << "file_size_bytes: " << (ioSize >= 0 ? std::to_string(ioSize) : "unknown (normal for network streams)") << '\n';
    oss << "metadata:\n";
    AppendMetadata(oss, formatContext->metadata, "  ");
    oss << '\n';
}

void AppendStreamInfo(std::ostringstream &oss, AVFormatContext *formatContext) {
    oss << "Demux Streams\n";
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        AVCodecParameters *parameters = stream->codecpar;
        oss << "stream #" << i << '\n';
        oss << "  media_type: " << MediaTypeName(parameters->codec_type) << '\n';
        oss << "  codec: " << CodecName(parameters) << '\n';
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
        }

        oss << "  metadata:\n";
        AppendMetadata(oss, stream->metadata, "    ");
        oss << '\n';
    }
}

StreamProbeMetrics AppendPacketSamples(std::ostringstream &oss, AVFormatContext *formatContext, int64_t readStartUs) {
    StreamProbeMetrics metrics;
    oss << "Demuxed Packet Samples\n";
    oss << "api: av_read_frame\n";
    oss << "note: this section shows compressed AVPacket samples pulled from the selected HLS stream.\n";

    AVPacket *packet = av_packet_alloc();
    if (packet == nullptr) {
        oss << "error: av_packet_alloc failed\n\n";
        return metrics;
    }

    int count = 0;
    while (count < 16) {
        const int result = av_read_frame(formatContext, packet);
        if (result < 0) {
            oss << "read_end: " << ErrorToString(result) << '\n';
            break;
        }

        if (metrics.firstPacketMs < 0) {
            metrics.firstPacketMs = (av_gettime_relative() - readStartUs) / 1000;
        }

        AVStream *stream = packet->stream_index >= 0 &&
            packet->stream_index < static_cast<int>(formatContext->nb_streams)
            ? formatContext->streams[packet->stream_index]
            : nullptr;
        AVRational timeBase = stream != nullptr ? stream->time_base : AVRational{1, AV_TIME_BASE};
        const char *typeName = stream != nullptr ? MediaTypeName(stream->codecpar->codec_type) : "unknown";

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

        const std::string packetLine = line.str();
        if (count == 0) {
            metrics.firstPacketLine = packetLine;
        }
        oss << packetLine << '\n';

        av_packet_unref(packet);
        count++;
    }

    av_packet_free(&packet);
    oss << '\n';
    return metrics;
}

std::string ProbeStreamingUrl(const std::string &url) {
    std::ostringstream oss;
    oss << "Streaming Topic Result\n\n";
    oss << "Focus Points\n";
    oss << "- master playlist / media playlist\n";
    oss << "- variant switching candidates\n";
    oss << "- ts / fMP4 segment style\n";
    oss << "- EXT-X-TARGETDURATION / EXTINF\n";
    oss << "- live / vod difference\n";
    oss << "- first packet latency / timeout settings / segment request candidates\n\n";

    oss << "Input\n";
    oss << "url: " << url << '\n';
    oss << "rw_timeout: 8000 ms\n";
    oss << "user_agent: FFmpegAVTutorial/StreamingTopic\n\n";

    const int64_t playlistFetchStartUs = av_gettime_relative();
    std::string playlistText;
    std::string playlistError;
    if (!FetchTextFromUrl(url, 8000, playlistText, playlistError)) {
        return "ERROR: playlist fetch failed: " + playlistError;
    }
    const int64_t playlistFetchMs = (av_gettime_relative() - playlistFetchStartUs) / 1000;

    const std::vector<std::string> lines = SplitLines(playlistText);
    const MasterPlaylistInfo masterInfo = ParseMasterPlaylist(url, lines);
    const MediaPlaylistInfo mediaInfo = ParseMediaPlaylist(url, lines);

    oss << "Playlist Fetch Timing\n";
    oss << "playlist_fetch_ms: " << playlistFetchMs << " ms\n";
    oss << "playlist_text_bytes: " << playlistText.size() << "\n\n";

    if (!masterInfo.variants.empty()) {
        AppendMasterPlaylistInfo(oss, url, masterInfo);
        if (!masterInfo.variants.front().resolvedUri.empty()) {
            std::string childText;
            std::string childError;
            const int64_t childFetchStartUs = av_gettime_relative();
            if (FetchTextFromUrl(masterInfo.variants.front().resolvedUri, 8000, childText, childError)) {
                const int64_t childFetchMs = (av_gettime_relative() - childFetchStartUs) / 1000;
                const MediaPlaylistInfo variantMedia = ParseMediaPlaylist(
                    masterInfo.variants.front().resolvedUri,
                    SplitLines(childText)
                );
                oss << "Selected Variant Preview\n";
                oss << "selected_variant_fetch_ms: " << childFetchMs << " ms\n";
                AppendMediaPlaylistInfo(oss, "First Variant Media Playlist", masterInfo.variants.front().resolvedUri, variantMedia);
            } else {
                oss << "Selected Variant Preview\n";
                oss << "fetch_error: " << childError << "\n\n";
            }
        }
    } else if (mediaInfo.isExtM3u) {
        AppendMediaPlaylistInfo(oss, "Root Playlist Analysis", url, mediaInfo);
    } else {
        oss << "Root Playlist Analysis\n";
        oss << "playlist_kind: not recognized as HLS text playlist\n\n";
    }

    AVFormatContext *formatContext = nullptr;
    AVDictionary *options = nullptr;
    av_dict_set(&options, "rw_timeout", "8000000", 0);
    av_dict_set(&options, "timeout", "8000000", 0);
    av_dict_set(&options, "user_agent", "FFmpegAVTutorial/StreamingTopic", 0);

    const int64_t openStartUs = av_gettime_relative();
    int result = avformat_open_input(&formatContext, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (result < 0) {
        return "ERROR: avformat_open_input failed: " + ErrorToString(result);
    }
    const int64_t openInputMs = (av_gettime_relative() - openStartUs) / 1000;
    NATIVE_LOGI_TAG("StreamingTopicDemo", "avformat_open_input success url=%s openMs=%lld", url.c_str(), static_cast<long long>(openInputMs));

    const int64_t streamInfoStartUs = av_gettime_relative();
    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) {
        const std::string error = ErrorToString(result);
        avformat_close_input(&formatContext);
        return "ERROR: avformat_find_stream_info failed: " + error;
    }
    const int64_t findStreamInfoMs = (av_gettime_relative() - streamInfoStartUs) / 1000;

    oss << "Network Demux Timing\n";
    oss << "open_input_ms: " << openInputMs << " ms\n";
    oss << "find_stream_info_ms: " << findStreamInfoMs << " ms\n\n";

    AppendFormatInfo(oss, formatContext);
    AppendStreamInfo(oss, formatContext);
    const StreamProbeMetrics metrics = AppendPacketSamples(oss, formatContext, av_gettime_relative());

    oss << "First Packet Latency\n";
    oss << "first_packet_ms: " << (metrics.firstPacketMs >= 0 ? std::to_string(metrics.firstPacketMs) + " ms" : "unknown") << '\n';
    oss << "first_packet_line: " << (metrics.firstPacketLine.empty() ? "none" : metrics.firstPacketLine) << "\n\n";

    const std::string resultText = oss.str();
    LogLongTextByChunk("StreamingTopicDemo", "streaming topic result", resultText);
    avformat_close_input(&formatContext);
    return resultText;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_probeStreamingUrl(
    JNIEnv *env,
    jobject /* obj */,
    jlong /* nativeHandle */,
    jstring url
) {
    const char *urlChars = env->GetStringUTFChars(url, nullptr);
    NATIVE_LOGI_TAG("StreamingTopicDemo", "probeStreamingUrl url=%s", urlChars);
    const std::string result = ProbeStreamingUrl(urlChars);
    env->ReleaseStringUTFChars(url, urlChars);
    return env->NewStringUTF(result.c_str());
}
