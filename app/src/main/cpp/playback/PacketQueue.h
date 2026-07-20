#pragma once

#include <cstdint>
#include <memory>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
}

enum class PacketQueueReadResult {
    Packet,
    Eof,
    Error
};

class PacketQueue {
public:
    explicit PacketQueue(size_t maxQueuedPackets);
    ~PacketQueue();

    void Reset();
    bool Push(AVPacket *packet);
    PacketQueueReadResult Read(AVPacket *packet, std::string *errorMessage);
    void Finish();
    void Fail(const std::string &errorMessage);
    void Stop();

    int64_t GetQueuedPacketCount() const;
    int64_t GetQueuedPacketBytes() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
