#pragma once
//PIMPL
#include <memory>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/frame.h"
}

namespace tutorial_utils {

class AudioPcmWriter {
public:
    AudioPcmWriter();
    ~AudioPcmWriter();

    std::string Open(AVCodecContext *codecContext, const std::string &path);
    int WriteFrame(const AVFrame *frame, std::string *error);
    std::string Summary() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

class VideoYuvWriter {
public:
    VideoYuvWriter();
    ~VideoYuvWriter();

    std::string Open(AVCodecContext *codecContext, const std::string &path);
    int WriteFrame(const AVFrame *frame, std::string *error);
    std::string Summary() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace tutorial_utils
