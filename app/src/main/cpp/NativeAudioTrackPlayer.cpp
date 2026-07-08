#include "NativeAudioTrackPlayer.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <thread>
#include <utility>
#include <vector>

#include "FfmpegAudioDecoder.h"
#include "NativeJniBindings.h"
#include "NativeLog.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/channel_layout.h"
#include "libavutil/samplefmt.h"
#include "libswresample/swresample.h"
}

namespace {

constexpr const char *kTag = "NativeAudioTrackPlayer";
constexpr int kStreamMusic = 3;
constexpr int kChannelOutMono = 0x4;
constexpr int kChannelOutStereo = 0xC;
constexpr int kEncodingPcm16Bit = 2;
constexpr int kModeStream = 1;

std::string AvErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_make_error_string(buffer, sizeof(buffer), errorCode);
    return buffer;
}

const char *SafeCString(const char *value, const char *fallback = "-") {
    return value != nullptr ? value : fallback;
}

std::string FormatSeconds(double value) {
    if (value < 0.0) {
        return "unknown";
    }
    std::ostringstream oss;
    oss.setf(std::ios::fixed);
    oss.precision(3);
    oss << value;
    return oss.str();
}

enum class PlayerState {
    Idle,
    Starting,
    Playing,
    Paused,
    Stopping,
    Stopped,
    Finished,
    Error
};

const char *StateName(PlayerState state) {
    switch (state) {
        case PlayerState::Idle:
            return "idle";
        case PlayerState::Starting:
            return "starting";
        case PlayerState::Playing:
            return "playing";
        case PlayerState::Paused:
            return "paused";
        case PlayerState::Stopping:
            return "stopping";
        case PlayerState::Stopped:
            return "stopped";
        case PlayerState::Finished:
            return "finished";
        case PlayerState::Error:
            return "error";
        default:
            return "unknown";
    }
}

}  // namespace

struct NativeAudioTrackPlayer::Impl {
    Impl(
        JavaVM *javaVm,
        std::string sourcePath,
        jobject nativeInstance,
        jmethodID onEventMethod
    )
        : javaVm(javaVm),
          inputPath(std::move(sourcePath)),
          callbackJni{nativeInstance, onEventMethod} {}

    ~Impl() {
        StopInternal(false);
        ReleaseAudioTrackJniRefs(javaVm, &audioTrackJni);
        ReleasePlayerCallbackJniRefs(javaVm, &callbackJni);
    }

    std::string Start(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(stateMutex);
        if (worker.joinable()) {
            return SnapshotLocked();
        }
        std::string initError = InitializeAudioTrackJni(env, &audioTrackJni);
        if (!initError.empty()) {
            state = PlayerState::Error;
            message = initError;
            lastError = initError;
            return SnapshotLocked();
        }

        state = PlayerState::Starting;
        message = "Preparing FFmpeg audio decode and native AudioTrack playback.";
        stopRequested = false;
        pauseRequested = false;
        audioTrackPaused = false;
        finishedNaturally = false;
        worker = std::thread(&Impl::PlaybackThreadMain, this);
        return SnapshotLocked();
    }

    std::string Pause() {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!worker.joinable()) {
                return SnapshotLocked();
            }
            pauseRequested = true;
            state = PlayerState::Paused;
            message = "Pause requested from Java. Native playback thread will wait.";
        }
        pauseCondition.notify_all();
        NotifyEvent(PlayerState::Paused);
        return Snapshot();
    }

    std::string Resume() {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (!worker.joinable()) {
                return SnapshotLocked();
            }
            pauseRequested = false;
            state = PlayerState::Playing;
            message = "Resume requested from Java. Native playback thread will continue.";
        }
        pauseCondition.notify_all();
        NotifyEvent(PlayerState::Playing);
        return Snapshot();
    }

    std::string Stop() {
        StopInternal(true);
        std::lock_guard<std::mutex> lock(stateMutex);
        return SnapshotLocked();
    }

    std::string Snapshot() const {
        std::lock_guard<std::mutex> lock(stateMutex);
        return SnapshotLocked();
    }

    void StopInternal(bool markStoppedState) {
        stopRequested = true;
        pauseRequested = false;
        pauseCondition.notify_all();
        if (markStoppedState) {
            SetState(PlayerState::Stopping, "Stopping playback and draining native resources.");
        }
        if (worker.joinable()) {
            worker.join();
        }
        if (markStoppedState) {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (state == PlayerState::Playing ||
                state == PlayerState::Paused ||
                state == PlayerState::Starting ||
                state == PlayerState::Stopping) {
                state = PlayerState::Stopped;
                message = "Playback stopped from Java.";
            }
        }
    }

    void SetState(PlayerState newState, const std::string &newMessage) {
        std::lock_guard<std::mutex> lock(stateMutex);
        state = newState;
        message = newMessage;
    }

    void SetError(const std::string &newMessage) {
        std::lock_guard<std::mutex> lock(stateMutex);
        state = PlayerState::Error;
        message = newMessage;
        lastError = newMessage;
    }

    void NotifyEvent(PlayerState eventState) {
        if (javaVm == nullptr || callbackJni.nativeInstance == nullptr || callbackJni.onEvent == nullptr) {
            return;
        }

        JNIEnv *env = nullptr;
        bool attached = false;
        if (javaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                return;
            }
            attached = true;
        }

        std::string snapshot = Snapshot();
        jstring stateString = env->NewStringUTF(StateName(eventState));
        jstring snapshotString = env->NewStringUTF(snapshot.c_str());
        if (stateString != nullptr && snapshotString != nullptr) {
            env->CallVoidMethod(callbackJni.nativeInstance, callbackJni.onEvent, stateString, snapshotString);
            ConsumeJavaException(env, "NativeAudioTrackPlayer.onNativeAudioTrackPlayerEvent");
        }
        if (stateString != nullptr) {
            env->DeleteLocalRef(stateString);
        }
        if (snapshotString != nullptr) {
            env->DeleteLocalRef(snapshotString);
        }

        if (attached) {
            javaVm->DetachCurrentThread();
        }
    }

    std::string SnapshotLocked() const {
        std::ostringstream oss;
        oss << "state: " << StateName(state) << '\n';
        oss << "message: " << message << '\n';
        oss << "input_path: " << inputPath << '\n';
        oss << "container: " << containerName << '\n';
        oss << "codec: " << codecName << '\n';
        oss << "source_sample_rate: " << sourceSampleRate << '\n';
        oss << "source_channels: " << sourceChannels << '\n';
        oss << "source_sample_fmt: " << sourceSampleFormat << '\n';
        oss << "output_sample_rate: " << outputSampleRate << '\n';
        oss << "output_channels: " << outputChannels << '\n';
        oss << "duration_sec: " << FormatSeconds(durationSec) << '\n';
        oss << "packet_count: " << packetCount << '\n';
        oss << "decoded_frames: " << decodedFrames << '\n';
        oss << "written_blocks: " << writtenBlocks << '\n';
        oss << "written_bytes: " << writtenBytes << '\n';
        if (!lastError.empty()) {
            oss << "last_error: " << lastError << '\n';
        }
        oss << "stop_requested: " << (stopRequested ? "true" : "false") << '\n';
        oss << "natural_finish: " << (finishedNaturally ? "true" : "false") << '\n';
        return oss.str();
    }

    void PlaybackThreadMain() {
        JNIEnv *env = nullptr;
        if (javaVm == nullptr || javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            SetError("Unable to attach playback thread to the JVM.");
            return;
        }

        RunPlayback(env);
        bool shouldNotifyError = false;
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            shouldNotifyError = state == PlayerState::Error;
        }
        if (shouldNotifyError) {
            NotifyEvent(PlayerState::Error);
        }
        javaVm->DetachCurrentThread();
    }

    bool HandlePauseIfNeeded(JNIEnv *env, jobject audioTrackLocal) {
        bool shouldSendPausedEvent = false;
        bool shouldSendPlayingEvent = false;

        std::unique_lock<std::mutex> lock(stateMutex);
        while (pauseRequested && !stopRequested) {
            if (!audioTrackPaused && audioTrackLocal != nullptr) {
                lock.unlock();
                env->CallVoidMethod(audioTrackLocal, audioTrackJni.pause);
                std::string javaError = ConsumeJavaException(env, "AudioTrack.pause");
                if (!javaError.empty()) {
                    SetError(javaError);
                    return false;
                }
                lock.lock();
                audioTrackPaused = true;
                state = PlayerState::Paused;
                message = "Native playback thread paused and waiting for resume.";
                shouldSendPausedEvent = true;
            }
            pauseCondition.wait_for(lock, std::chrono::milliseconds(50));
        }

        if (stopRequested) {
            return false;
        }

        if (audioTrackPaused && audioTrackLocal != nullptr) {
            lock.unlock();
            env->CallVoidMethod(audioTrackLocal, audioTrackJni.play);
            std::string javaError = ConsumeJavaException(env, "AudioTrack.play");
            if (!javaError.empty()) {
                SetError(javaError);
                return false;
            }
            lock.lock();
            audioTrackPaused = false;
            state = PlayerState::Playing;
            message = "Playback resumed and AudioTrack restarted.";
            shouldSendPlayingEvent = true;
        }
        lock.unlock();

        if (shouldSendPausedEvent) {
            NotifyEvent(PlayerState::Paused);
        }
        if (shouldSendPlayingEvent) {
            NotifyEvent(PlayerState::Playing);
        }
        return true;
    }

    void RunPlayback(JNIEnv *env) {
        jobject audioTrackLocal = nullptr;
        int outputChannelConfig = kChannelOutStereo;
        int outputBufferSize = 0;
        FfmpegAudioDecoder decoder(inputPath);

        auto cleanup = [&]() {
            if (env != nullptr && audioTrackLocal != nullptr) {
                env->CallVoidMethod(audioTrackLocal, audioTrackJni.pause);
                ConsumeJavaException(env, "AudioTrack.pause");
                env->CallVoidMethod(audioTrackLocal, audioTrackJni.flush);
                ConsumeJavaException(env, "AudioTrack.flush");
                env->CallVoidMethod(audioTrackLocal, audioTrackJni.stop);
                ConsumeJavaException(env, "AudioTrack.stop");
                env->CallVoidMethod(audioTrackLocal, audioTrackJni.release);
                ConsumeJavaException(env, "AudioTrack.release");
                env->DeleteLocalRef(audioTrackLocal);
                audioTrackLocal = nullptr;
            }
        };

        auto writePcmToAudioTrack = [&](const uint8_t *data, int size) -> bool {
            if (!HandlePauseIfNeeded(env, audioTrackLocal)) {
                return false;
            }
            jbyteArray byteArray = env->NewByteArray(size);
            if (byteArray == nullptr) {
                SetError("AudioTrack write failed: unable to allocate Java byte array.");
                return false;
            }
            env->SetByteArrayRegion(byteArray, 0, size, reinterpret_cast<const jbyte *>(data));
            std::string javaError = ConsumeJavaException(env, "AudioTrack SetByteArrayRegion");
            if (!javaError.empty()) {
                env->DeleteLocalRef(byteArray);
                SetError(javaError);
                return false;
            }

            jint writeResult = env->CallIntMethod(audioTrackLocal, audioTrackJni.write, byteArray, 0, size);
            javaError = ConsumeJavaException(env, "AudioTrack.write");
            env->DeleteLocalRef(byteArray);
            if (!javaError.empty()) {
                SetError(javaError);
                return false;
            }
            if (writeResult < 0) {
                std::ostringstream oss;
                oss << "AudioTrack.write returned error code " << writeResult << '.';
                SetError(oss.str());
                return false;
            }

            std::lock_guard<std::mutex> lock(stateMutex);
            writtenBlocks += 1;
            writtenBytes += writeResult;
            NATIVE_LOGD_TAG(
                kTag,
                "audio write block=%lld writeBytes=%d cumulativeBytes=%lld",
                static_cast<long long>(writtenBlocks),
                static_cast<int>(writeResult),
                static_cast<long long>(writtenBytes)
            );
            return true;
        };

        const std::string decoderOpenError = decoder.Open();
        if (!decoderOpenError.empty()) {
            SetError(decoderOpenError);
            cleanup();
            return;
        }

        const AudioDecoderStreamInfo &streamInfo = decoder.GetStreamInfo();
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            containerName = streamInfo.containerName;
            codecName = streamInfo.codecName;
            sourceSampleRate = streamInfo.sourceSampleRate;
            sourceChannels = streamInfo.sourceChannels;
            sourceSampleFormat = streamInfo.sourceSampleFormat;
            outputSampleRate = streamInfo.outputSampleRate;
            outputChannels = streamInfo.outputChannels;
            durationSec = streamInfo.durationSec;
            packetCount = decoder.GetPacketCount();
            decodedFrames = decoder.GetDecodedFrameCount();
        }

        outputChannelConfig = outputChannels == 1 ? kChannelOutMono : kChannelOutStereo;

        jint minBufferSize = env->CallStaticIntMethod(
            audioTrackJni.audioTrackClass,
            audioTrackJni.getMinBufferSize,
            outputSampleRate,
            outputChannelConfig,
            kEncodingPcm16Bit
        );
        std::string javaError = ConsumeJavaException(env, "AudioTrack.getMinBufferSize");
        if (!javaError.empty()) {
            SetError(javaError);
            cleanup();
            return;
        }
        if (minBufferSize <= 0) {
            SetError("AudioTrack.getMinBufferSize returned an invalid size.");
            cleanup();
            return;
        }

        outputBufferSize = std::max(minBufferSize * 4, outputSampleRate * outputChannels * 2 / 5);
        audioTrackLocal = env->NewObject(
            audioTrackJni.audioTrackClass,
            audioTrackJni.constructor,
            kStreamMusic,
            outputSampleRate,
            outputChannelConfig,
            kEncodingPcm16Bit,
            outputBufferSize,
            kModeStream
        );
        javaError = ConsumeJavaException(env, "AudioTrack.<init>");
        if (!javaError.empty() || audioTrackLocal == nullptr) {
            SetError(javaError.empty() ? "Failed to construct AudioTrack from native code." : javaError);
            cleanup();
            return;
        }

        env->CallVoidMethod(audioTrackLocal, audioTrackJni.play);
        javaError = ConsumeJavaException(env, "AudioTrack.play");
        if (!javaError.empty()) {
            SetError(javaError);
            cleanup();
            return;
        }

        {
            std::ostringstream oss;
            oss << "Decoding audio with FFmpeg and writing PCM into Android AudioTrack from native.";
            SetState(PlayerState::Playing, oss.str());
        }
        NotifyEvent(PlayerState::Playing);
        NATIVE_LOGI_TAG(
            kTag,
            "playback started path=%s container=%s codec=%s srcRate=%d srcChannels=%d outRate=%d outChannels=%d bufferSize=%d",
            decoder.GetInputPath().c_str(),
            containerName.c_str(),
            codecName.c_str(),
            sourceSampleRate,
            sourceChannels,
            outputSampleRate,
            outputChannels,
            outputBufferSize
        );

        while (!stopRequested) {
            if (!HandlePauseIfNeeded(env, audioTrackLocal)) {
                if (stopRequested) {
                    break;
                }
                cleanup();
                return;
            }

            AudioDecoderPcmBlock pcmBlock;
            std::string decodeError;
            const AudioDecoderReadResult readResult = decoder.ReadNextPcmBlock(&pcmBlock, &decodeError);
            if (readResult == AudioDecoderReadResult::Eof) {
                break;
            }
            if (readResult == AudioDecoderReadResult::Error) {
                SetError(decodeError);
                cleanup();
                return;
            }

            {
                std::lock_guard<std::mutex> lock(stateMutex);
                packetCount = decoder.GetPacketCount();
                decodedFrames = decoder.GetDecodedFrameCount();
            }
            if (!writePcmToAudioTrack(pcmBlock.pcmData.data(), static_cast<int>(pcmBlock.pcmData.size()))) {
                cleanup();
                return;
            }
        }

        {
            std::lock_guard<std::mutex> lock(stateMutex);
            packetCount = decoder.GetPacketCount();
            decodedFrames = decoder.GetDecodedFrameCount();
            finishedNaturally = !stopRequested;
            if (stopRequested) {
                state = PlayerState::Stopped;
                message = "Playback stopped from Java.";
            } else {
                state = PlayerState::Finished;
                message = "Audio playback reached end of stream.";
            }
        }
        NotifyEvent(stopRequested ? PlayerState::Stopped : PlayerState::Finished);
        NATIVE_LOGI_TAG(
            kTag,
            "playback ended state=%s decodedFrames=%lld writtenBlocks=%lld writtenBytes=%lld",
            stopRequested ? "stopped" : "finished",
            static_cast<long long>(decodedFrames),
            static_cast<long long>(writtenBlocks),
            static_cast<long long>(writtenBytes)
        );

        cleanup();
    }

    JavaVM *javaVm = nullptr;
    std::string inputPath;
    AudioTrackJni audioTrackJni{};
    PlayerCallbackJni callbackJni{};
    std::thread worker;
    mutable std::mutex stateMutex;
    std::condition_variable pauseCondition;
    std::atomic<bool> stopRequested{false};
    bool pauseRequested = false;
    bool audioTrackPaused = false;
    bool finishedNaturally = false;
    PlayerState state = PlayerState::Idle;
    std::string message = "Idle.";
    std::string lastError;
    std::string containerName = "-";
    std::string codecName = "-";
    std::string sourceSampleFormat = "-";
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int outputSampleRate = 0;
    int outputChannels = 0;
    double durationSec = -1.0;
    int64_t packetCount = 0;
    int64_t decodedFrames = 0;
    int64_t writtenBlocks = 0;
    int64_t writtenBytes = 0;
};

NativeAudioTrackPlayer::NativeAudioTrackPlayer(
    JavaVM *javaVm,
    std::string inputPath,
    jobject nativeInstance,
    jmethodID onEventMethod
) : impl_(std::make_unique<Impl>(javaVm, std::move(inputPath), nativeInstance, onEventMethod)) {}

NativeAudioTrackPlayer::~NativeAudioTrackPlayer() = default;

std::string NativeAudioTrackPlayer::Start(JNIEnv *env) {
    return impl_->Start(env);
}

std::string NativeAudioTrackPlayer::Pause() {
    return impl_->Pause();
}

std::string NativeAudioTrackPlayer::Resume() {
    return impl_->Resume();
}

std::string NativeAudioTrackPlayer::Stop() {
    return impl_->Stop();
}

std::string NativeAudioTrackPlayer::Snapshot() const {
    return impl_->Snapshot();
}
