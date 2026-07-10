#pragma once

#include <cstdint>
#include <memory>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
}

enum class AudioPacketReadResult {
    Packet,
    Eof,
    Error
};

struct AudioPacketStreamInfo {
    std::string containerName = "-";
    std::string codecName = "-";
    std::string sourceSampleFormat = "-";
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    double durationSec = -1.0;
    int audioStreamIndex = -1;
    AVCodecID codecId = AV_CODEC_ID_NONE;
    AVRational streamTimeBase{0, 1};
};

class PacketReader {
public:
    explicit PacketReader(std::string inputPath);
    ~PacketReader();

    std::string Open();
    AudioPacketReadResult ReadNextAudioPacket(AVPacket *packet, std::string *errorMessage);

    const std::string &GetInputPath() const;
    const AudioPacketStreamInfo &GetStreamInfo() const;
    const AVCodecParameters *GetAudioCodecParameters() const;
    AVRational GetAudioStreamTimeBase() const;
    int64_t GetPacketCount() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
