#pragma once

#include <jni.h>

#include <memory>
#include <string>

class NativeAudioTrackPlayer {
public:
    NativeAudioTrackPlayer(
        JavaVM *javaVm,
        std::string inputPath,
        jobject nativeInstance,
        jmethodID onEventMethod
    );
    ~NativeAudioTrackPlayer();

    std::string Start(JNIEnv *env);
    std::string Pause();
    std::string Resume();
    std::string Stop();
    std::string Snapshot() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
