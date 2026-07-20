#include <jni.h>

#include <iomanip>
#include <sstream>
#include <string>

#include "NativeLog.h"

extern "C" {
#include "libavutil/avutil.h"
#include "libavutil/mathematics.h"
#include "libavutil/rational.h"
}

namespace {

std::string RationalToString(AVRational value) {
    std::ostringstream oss;
    oss << value.num << '/' << value.den;
    return oss.str();
}

std::string RationalToDecimalString(AVRational value, int precision = 6) {
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(precision) << av_q2d(value);
    return oss.str();
}

void LogRationalValue(const char *label, AVRational value, bool withDecimal = false) {
    if (withDecimal && value.den != 0) {
        NATIVE_LOGI_TAG(
            "AVRationalDemo",
            "%s = %s ~= %s",
            label,
            RationalToString(value).c_str(),
            RationalToDecimalString(value).c_str()
        );
        return;
    }
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "%s = %s",
        label,
        RationalToString(value).c_str()
    );
}

void RunAvRationalDemo() {
    const AVRational timeBaseMs = av_make_q(1, 1000);
    const AVRational timeBaseUs = AV_TIME_BASE_Q;
    const AVRational fps30 = av_make_q(30, 1);
    const AVRational fpsNtsc = av_make_q(30000, 1001);
    const AVRational half = av_make_q(1, 2);
    const AVRational third = av_make_q(1, 3);

    int reducedNum = 0;
    int reducedDen = 0;
    const int reducedExact = av_reduce(&reducedNum, &reducedDen, 100, 200, 1000);
    const AVRational reduced = av_make_q(reducedNum, reducedDen);

    const AVRational product = av_mul_q(half, third);
    const AVRational quotient = av_div_q(half, third);
    const AVRational sum = av_add_q(half, third);
    const AVRational difference = av_sub_q(half, third);
    const AVRational inverse = av_inv_q(fps30);
    const AVRational fromDouble = av_d2q(29.97, 1000000);

    const int cmpHalfThird = av_cmp_q(half, third);
    const int nearerResult = av_nearer_q(av_make_q(2997, 100), fps30, fpsNtsc);

    static const AVRational fpsCandidates[] = {
        {24, 1},
        {25, 1},
        {30, 1},
        {30000, 1001},
        {60, 1},
        {0, 0}
    };
    const AVRational targetFps = av_make_q(2997, 100);
    const int nearestIndex = av_find_nearest_q_idx(targetFps, fpsCandidates);

    const int64_t audioPts = 44100;
    const int64_t audioPtsUs = av_rescale_q(audioPts, av_make_q(1, 44100), timeBaseUs);
    const int64_t videoPts = 135;
    const int64_t videoPtsMs = av_rescale_q(videoPts, av_make_q(1, 30), timeBaseMs);
    const int64_t videoPtsUsRoundedDown = av_rescale_q_rnd(
        videoPts,
        av_make_q(1, 30),
        timeBaseUs,
        AV_ROUND_DOWN
    );
    const int64_t videoPtsUsRoundedNear = av_rescale_q_rnd(
        videoPts,
        av_make_q(1, 30),
        timeBaseUs,
        AV_ROUND_NEAR_INF
    );

    NATIVE_LOGI_TAG("AVRationalDemo", "========== AVRational API Demo ==========");
    NATIVE_LOGI_TAG("AVRationalDemo", "[1] Struct Basics");
    NATIVE_LOGI_TAG("AVRationalDemo", "AVRational fields: num=numerator, den=denominator");
    NATIVE_LOGI_TAG("AVRationalDemo", "Typical usage: time_base, frame rate, sample aspect ratio, pts conversion");

    NATIVE_LOGI_TAG("AVRationalDemo", "[2] Create AVRational Values");
    LogRationalValue("av_make_q(1, 1000)", timeBaseMs, true);
    LogRationalValue("av_make_q(30, 1)", fps30, true);
    LogRationalValue("av_make_q(30000, 1001)", fpsNtsc, true);
    LogRationalValue("AV_TIME_BASE_Q", timeBaseUs, true);
    LogRationalValue("av_d2q(29.97, 1000000)", fromDouble, true);

    NATIVE_LOGI_TAG("AVRationalDemo", "[3] Reduce / Normalize");
    NATIVE_LOGI_TAG("AVRationalDemo", "input fraction = 100/200, max = 1000");
    LogRationalValue("av_reduce result", reduced, true);
    NATIVE_LOGI_TAG("AVRationalDemo", "av_reduce exact = %s", reducedExact == 1 ? "true" : "false");

    NATIVE_LOGI_TAG("AVRationalDemo", "[4] Arithmetic APIs");
    LogRationalValue("1/2 + 1/3", sum, true);
    LogRationalValue("1/2 - 1/3", difference, true);
    LogRationalValue("1/2 * 1/3", product, true);
    LogRationalValue("1/2 / 1/3", quotient, true);
    LogRationalValue("av_inv_q(30/1)", inverse, true);

    NATIVE_LOGI_TAG("AVRationalDemo", "[5] Compare / Choose Nearest");
    LogRationalValue("target fps", targetFps, true);
    NATIVE_LOGI_TAG("AVRationalDemo", "av_cmp_q(1/2, 1/3) = %d", cmpHalfThird);
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_nearer_q(target, 30/1, 30000/1001) = %d",
        nearerResult
    );
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_find_nearest_q_idx(target, [24,25,30,30000/1001,60]) = %d, value = %s",
        nearestIndex,
        RationalToString(fpsCandidates[nearestIndex]).c_str()
    );

    NATIVE_LOGI_TAG("AVRationalDemo", "[6] Time Base Conversion APIs");
    NATIVE_LOGI_TAG("AVRationalDemo", "audio pts = %lld, source time_base = 1/44100", static_cast<long long>(audioPts));
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_rescale_q(audioPts, 1/44100, AV_TIME_BASE_Q) = %lld us",
        static_cast<long long>(audioPtsUs)
    );
    NATIVE_LOGI_TAG("AVRationalDemo", "video pts = %lld, source time_base = 1/30", static_cast<long long>(videoPts));
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_rescale_q(videoPts, 1/30, 1/1000) = %lld ms",
        static_cast<long long>(videoPtsMs)
    );
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_rescale_q_rnd(videoPts, 1/30, AV_TIME_BASE_Q, AV_ROUND_DOWN) = %lld us",
        static_cast<long long>(videoPtsUsRoundedDown)
    );
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "av_rescale_q_rnd(videoPts, 1/30, AV_TIME_BASE_Q, AV_ROUND_NEAR_INF) = %lld us",
        static_cast<long long>(videoPtsUsRoundedNear)
    );

    NATIVE_LOGI_TAG("AVRationalDemo", "[7] How To Read pts * time_base");
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "example: pts = 135, tb = 1/30 => seconds = 135 * av_q2d(1/30) = %.6f",
        135.0 * av_q2d(av_make_q(1, 30))
    );

    NATIVE_LOGI_TAG("AVRationalDemo", "[8] Common FFmpeg Scenarios");
    NATIVE_LOGI_TAG("AVRationalDemo", "- AVStream.time_base");
    NATIVE_LOGI_TAG("AVRationalDemo", "- AVCodecContext.time_base");
    NATIVE_LOGI_TAG("AVRationalDemo", "- AVFrame.sample_aspect_ratio");
    NATIVE_LOGI_TAG("AVRationalDemo", "- frame rate and packet duration conversion");
    NATIVE_LOGI_TAG("AVRationalDemo", "- pts/dts conversion between codec, stream, and wall-clock units");
    NATIVE_LOGI_TAG("AVRationalDemo", "========== AVRational API Demo Finished ==========");
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_runAvRationalDemo(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong nativeHandle
) {
    NATIVE_LOGI_TAG(
        "AVRationalDemo",
        "runAvRationalDemo nativeHandle=%lld",
        static_cast<long long>(nativeHandle)
    );
    RunAvRationalDemo();
}
