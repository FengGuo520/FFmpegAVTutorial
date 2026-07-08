#pragma once

#include <jni.h>

#include <string>

struct AudioTrackJni {
    jclass audioTrackClass = nullptr;
    jmethodID constructor = nullptr;
    jmethodID play = nullptr;
    jmethodID pause = nullptr;
    jmethodID stop = nullptr;
    jmethodID flush = nullptr;
    jmethodID release = nullptr;
    jmethodID write = nullptr;
    jmethodID getPlayState = nullptr;
    jmethodID getMinBufferSize = nullptr;
};

struct PlayerCallbackJni {
    jobject nativeInstance = nullptr;
    jmethodID onEvent = nullptr;
};

std::string ConsumeJavaException(JNIEnv *env, const char *context);
std::string InitializeAudioTrackJni(JNIEnv *env, AudioTrackJni *audioTrackJni);
void ReleaseAudioTrackJniRefs(JavaVM *javaVm, AudioTrackJni *audioTrackJni);
void ReleasePlayerCallbackJniRefs(JavaVM *javaVm, PlayerCallbackJni *callbackJni);
