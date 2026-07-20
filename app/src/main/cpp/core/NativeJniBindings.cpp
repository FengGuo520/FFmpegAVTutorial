#include "NativeJniBindings.h"

#include <sstream>

namespace {

// Ensure the current thread owns a valid JNIEnv. If the thread was not created by the JVM,
// this helper attaches it on demand and reports that via `attached` so the caller can detach later.
bool AttachEnvIfNeeded(JavaVM *javaVm, JNIEnv **env, bool *attached) {
    if (javaVm == nullptr || env == nullptr || attached == nullptr) {
        return false;
    }
    *attached = false;
    if (javaVm->GetEnv(reinterpret_cast<void **>(env), JNI_VERSION_1_6) == JNI_OK) {
        return true;
    }
    if (javaVm->AttachCurrentThread(env, nullptr) != JNI_OK) {
        return false;
    }
    *attached = true;
    return true;
}

}  // namespace

std::string ConsumeJavaException(JNIEnv *env, const char *context) {
    if (!env->ExceptionCheck()) {
        return {};
    }
    env->ExceptionDescribe();
    env->ExceptionClear();
    std::ostringstream oss;
    oss << context << " raised a Java exception.";
    return oss.str();
}

std::string InitializeAudioTrackJni(JNIEnv *env, AudioTrackJni *audioTrackJni) {
    if (env == nullptr || audioTrackJni == nullptr) {
        return "InitializeAudioTrackJni received a null argument.";
    }
    if (audioTrackJni->audioTrackClass != nullptr) {
        return {};
    }

    jclass localClass = env->FindClass("android/media/AudioTrack");
    if (localClass == nullptr) {
        std::string error = ConsumeJavaException(env, "FindClass(android/media/AudioTrack)");
        if (error.empty()) {
            error = "Unable to find android.media.AudioTrack.";
        }
        return error;
    }

    audioTrackJni->audioTrackClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (audioTrackJni->audioTrackClass == nullptr) {
        return "Unable to create a global reference for AudioTrack.";
    }

    audioTrackJni->constructor = env->GetMethodID(audioTrackJni->audioTrackClass, "<init>", "(IIIIII)V");
    audioTrackJni->play = env->GetMethodID(audioTrackJni->audioTrackClass, "play", "()V");
    audioTrackJni->pause = env->GetMethodID(audioTrackJni->audioTrackClass, "pause", "()V");
    audioTrackJni->stop = env->GetMethodID(audioTrackJni->audioTrackClass, "stop", "()V");
    audioTrackJni->flush = env->GetMethodID(audioTrackJni->audioTrackClass, "flush", "()V");
    audioTrackJni->release = env->GetMethodID(audioTrackJni->audioTrackClass, "release", "()V");
    audioTrackJni->write = env->GetMethodID(audioTrackJni->audioTrackClass, "write", "([BII)I");
    audioTrackJni->getPlayState = env->GetMethodID(audioTrackJni->audioTrackClass, "getPlayState", "()I");
    audioTrackJni->getMinBufferSize =
        env->GetStaticMethodID(audioTrackJni->audioTrackClass, "getMinBufferSize", "(III)I");

    if (audioTrackJni->constructor == nullptr ||
        audioTrackJni->play == nullptr ||
        audioTrackJni->pause == nullptr ||
        audioTrackJni->stop == nullptr ||
        audioTrackJni->flush == nullptr ||
        audioTrackJni->release == nullptr ||
        audioTrackJni->write == nullptr ||
        audioTrackJni->getPlayState == nullptr ||
        audioTrackJni->getMinBufferSize == nullptr) {
        return "Failed to resolve one or more AudioTrack JNI methods.";
    }

    return {};
}

void ReleaseAudioTrackJniRefs(JavaVM *javaVm, AudioTrackJni *audioTrackJni) {
    if (audioTrackJni == nullptr || audioTrackJni->audioTrackClass == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (!AttachEnvIfNeeded(javaVm, &env, &attached)) {
        return;
    }

    env->DeleteGlobalRef(audioTrackJni->audioTrackClass);
    audioTrackJni->audioTrackClass = nullptr;
    audioTrackJni->constructor = nullptr;
    audioTrackJni->play = nullptr;
    audioTrackJni->pause = nullptr;
    audioTrackJni->stop = nullptr;
    audioTrackJni->flush = nullptr;
    audioTrackJni->release = nullptr;
    audioTrackJni->write = nullptr;
    audioTrackJni->getPlayState = nullptr;
    audioTrackJni->getMinBufferSize = nullptr;

    if (attached) {
        javaVm->DetachCurrentThread();
    }
}

void ReleasePlayerCallbackJniRefs(JavaVM *javaVm, PlayerCallbackJni *callbackJni) {
    if (callbackJni == nullptr) {
        return;
    }
    if (callbackJni->nativeInstance == nullptr && callbackJni->onEvent == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (!AttachEnvIfNeeded(javaVm, &env, &attached)) {
        return;
    }

    if (callbackJni->nativeInstance != nullptr) {
        env->DeleteGlobalRef(callbackJni->nativeInstance);
        callbackJni->nativeInstance = nullptr;
    }
    callbackJni->onEvent = nullptr;

    if (attached) {
        javaVm->DetachCurrentThread();
    }
}
