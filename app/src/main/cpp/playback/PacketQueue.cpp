#include "PacketQueue.h"

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <utility>

extern "C" {
#include "libavcodec/packet.h"
}

namespace {

struct QueuedPacket {
    QueuedPacket() = default;

    ~QueuedPacket() {
        av_packet_unref(&packet);
    }

    QueuedPacket(QueuedPacket &&other) noexcept {
        av_packet_move_ref(&packet, &other.packet);
    }

    QueuedPacket &operator=(QueuedPacket &&other) noexcept {
        if (this != &other) {
            av_packet_unref(&packet);
            av_packet_move_ref(&packet, &other.packet);
        }
        return *this;
    }

    QueuedPacket(const QueuedPacket &) = delete;
    QueuedPacket &operator=(const QueuedPacket &) = delete;

    AVPacket packet;
};

}  // namespace

struct PacketQueue::Impl {
    explicit Impl(size_t maxQueuedPackets) : maxQueuedPackets(maxQueuedPackets) {}

    void Reset() {
        std::lock_guard<std::mutex> lock(mutex);
        queue.clear();
        queuedBytes = 0;
        finished = false;
        failed = false;
        stopped = false;
        errorMessage.clear();
        dataCondition.notify_all();
        spaceCondition.notify_all();
    }

    bool Push(AVPacket *packet) {
        if (packet == nullptr) {
            return false;
        }

        std::unique_lock<std::mutex> lock(mutex);
        spaceCondition.wait(lock, [this]() {
            return stopped || failed || queue.size() < maxQueuedPackets;
        });
        if (stopped || failed) {
            return false;
        }

        QueuedPacket queuedPacket;
        av_packet_move_ref(&queuedPacket.packet, packet);
        queuedBytes += queuedPacket.packet.size;
        queue.push_back(std::move(queuedPacket));
        lock.unlock();
        dataCondition.notify_one();
        return true;
    }

    PacketQueueReadResult Read(AVPacket *packet, std::string *outErrorMessage) {
        if (packet == nullptr) {
            if (outErrorMessage != nullptr) {
                *outErrorMessage = "Packet queue read received a null AVPacket output.";
            }
            return PacketQueueReadResult::Error;
        }

        av_packet_unref(packet);
        std::unique_lock<std::mutex> lock(mutex);
        dataCondition.wait(lock, [this]() {
            return stopped || failed || finished || !queue.empty();
        });

        if (!queue.empty()) {
            QueuedPacket queuedPacket = std::move(queue.front());
            queue.pop_front();
            queuedBytes -= queuedPacket.packet.size;
            av_packet_move_ref(packet, &queuedPacket.packet);
            lock.unlock();
            spaceCondition.notify_one();
            return PacketQueueReadResult::Packet;
        }

        if (failed) {
            if (outErrorMessage != nullptr) {
                *outErrorMessage = errorMessage;
            }
            return PacketQueueReadResult::Error;
        }

        return PacketQueueReadResult::Eof;
    }

    void Finish() {
        std::lock_guard<std::mutex> lock(mutex);
        finished = true;
        dataCondition.notify_all();
    }

    void Fail(const std::string &newErrorMessage) {
        std::lock_guard<std::mutex> lock(mutex);
        failed = true;
        finished = true;
        errorMessage = newErrorMessage;
        dataCondition.notify_all();
        spaceCondition.notify_all();
    }

    void Stop() {
        std::lock_guard<std::mutex> lock(mutex);
        stopped = true;
        finished = true;
        dataCondition.notify_all();
        spaceCondition.notify_all();
    }

    int64_t GetQueuedPacketCount() const {
        std::lock_guard<std::mutex> lock(mutex);
        return static_cast<int64_t>(queue.size());
    }

    int64_t GetQueuedPacketBytes() const {
        std::lock_guard<std::mutex> lock(mutex);
        return queuedBytes;
    }

    const size_t maxQueuedPackets = 0;
    mutable std::mutex mutex;
    std::condition_variable dataCondition;
    std::condition_variable spaceCondition;
    std::deque<QueuedPacket> queue;
    int64_t queuedBytes = 0;
    bool finished = false;
    bool failed = false;
    bool stopped = false;
    std::string errorMessage;
};

PacketQueue::PacketQueue(size_t maxQueuedPackets)
    : impl_(std::make_unique<Impl>(maxQueuedPackets)) {}

PacketQueue::~PacketQueue() = default;

void PacketQueue::Reset() {
    impl_->Reset();
}

bool PacketQueue::Push(AVPacket *packet) {
    return impl_->Push(packet);
}

PacketQueueReadResult PacketQueue::Read(AVPacket *packet, std::string *errorMessage) {
    return impl_->Read(packet, errorMessage);
}

void PacketQueue::Finish() {
    impl_->Finish();
}

void PacketQueue::Fail(const std::string &errorMessage) {
    impl_->Fail(errorMessage);
}

void PacketQueue::Stop() {
    impl_->Stop();
}

int64_t PacketQueue::GetQueuedPacketCount() const {
    return impl_->GetQueuedPacketCount();
}

int64_t PacketQueue::GetQueuedPacketBytes() const {
    return impl_->GetQueuedPacketBytes();
}
