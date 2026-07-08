#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

enum class AudioDecoderReadResult {
    Block,
    Eof,
    Error
};

struct AudioDecoderStreamInfo {
    std::string containerName = "-";
    std::string codecName = "-";
    std::string sourceSampleFormat = "-";
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int outputSampleRate = 0;
    int outputChannels = 0;
    double durationSec = -1.0;
};

struct AudioDecoderPcmBlock {
    std::vector<uint8_t> pcmData;
    int64_t frameIndex = -1;
    int64_t framePts = 0;
    int frameNbSamples = 0;
    std::string frameFormat = "-";
    int frameSampleRate = 0;
    int frameChannels = 0;
};

class FfmpegAudioDecoder {
public:
    explicit FfmpegAudioDecoder(std::string inputPath);
    ~FfmpegAudioDecoder();

    std::string Open();
    AudioDecoderReadResult ReadNextPcmBlock(AudioDecoderPcmBlock *block, std::string *errorMessage);

    const std::string &GetInputPath() const;
    const AudioDecoderStreamInfo &GetStreamInfo() const;
    int64_t GetPacketCount() const;
    int64_t GetDecodedFrameCount() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
