#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "NativeAudioTrackPlayer.h"

namespace {

JavaVM *gJavaVm = nullptr;
std::mutex gPlayerMutex;

std::unique_ptr<NativeAudioTrackPlayer> gAudioPlayer;

std::string StopGlobalPlayerLocked() {
    if (!gAudioPlayer) {
        return "state: idle\nmessage: No native audio player is active.\n";
    }
    std::string snapshot = gAudioPlayer->Stop();
    gAudioPlayer.reset();
    return snapshot;
}

}  // namespace

extern "C" jint JNI_OnLoad(JavaVM *vm, void *) {
    gJavaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_startNativeAudioTrackPlayer(
    JNIEnv *env,
    jobject thiz,
    jlong,
    jstring mediaPath
) {
    const char *pathChars = env->GetStringUTFChars(mediaPath, nullptr);
    std::string mediaPathString = pathChars != nullptr ? pathChars : "";
    if (pathChars != nullptr) {
        env->ReleaseStringUTFChars(mediaPath, pathChars);
    }

    std::lock_guard<std::mutex> lock(gPlayerMutex);
    if (gAudioPlayer) {
        StopGlobalPlayerLocked();
    }

    jclass nativeClass = env->GetObjectClass(thiz);
    jmethodID onEventMethod = nativeClass != nullptr
        ? env->GetMethodID(nativeClass, "onNativeAudioTrackPlayerEvent", "(Ljava/lang/String;Ljava/lang/String;)V")
        : nullptr;
    if (nativeClass != nullptr) {
        env->DeleteLocalRef(nativeClass);
    }
    jobject nativeInstanceGlobal = env->NewGlobalRef(thiz);

    gAudioPlayer = std::make_unique<NativeAudioTrackPlayer>(
        gJavaVm,
        mediaPathString,
        nativeInstanceGlobal,
        onEventMethod
    );
    std::string result = gAudioPlayer->Start(env);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_stopNativeAudioTrackPlayer(
    JNIEnv *env,
    jobject,
    jlong
) {
    std::lock_guard<std::mutex> lock(gPlayerMutex);
    std::string result = StopGlobalPlayerLocked();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_pauseNativeAudioTrackPlayer(
    JNIEnv *env,
    jobject,
    jlong
) {
    std::lock_guard<std::mutex> lock(gPlayerMutex);
    std::string result = gAudioPlayer
        ? gAudioPlayer->Pause()
        : "state: idle\nmessage: No native audio player is active.\n";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_resumeNativeAudioTrackPlayer(
    JNIEnv *env,
    jobject,
    jlong
) {
    std::lock_guard<std::mutex> lock(gPlayerMutex);
    std::string result = gAudioPlayer
        ? gAudioPlayer->Resume()
        : "state: idle\nmessage: No native audio player is active.\n";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_getNativeAudioTrackPlayerSnapshot(
    JNIEnv *env,
    jobject,
    jlong
) {
    std::lock_guard<std::mutex> lock(gPlayerMutex);
    std::string snapshot = gAudioPlayer
        ? gAudioPlayer->Snapshot()
        : "state: idle\nmessage: No native audio player is active.\n";
    return env->NewStringUTF(snapshot.c_str());
}
