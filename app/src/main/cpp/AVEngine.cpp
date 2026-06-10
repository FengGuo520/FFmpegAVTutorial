//
// Created by 18790 on 2026/6/10.
//

#include <jni.h>
#include <string>

#include <sstream>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/codec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
}


namespace {

    std::string VersionToString(unsigned version) {
        std::ostringstream oss;
        oss << ((version >> 16) & 0xFF)
            << '.'
            << ((version >> 8) & 0xFF)
            << '.'
            << (version & 0xFF);
        return oss.str();
    }

    const char *MediaTypeName(AVMediaType mediaType) {
        switch (mediaType) {
            case AVMEDIA_TYPE_VIDEO:
                return "video";
            case AVMEDIA_TYPE_AUDIO:
                return "audio";
            case AVMEDIA_TYPE_SUBTITLE:
                return "subtitle";
            case AVMEDIA_TYPE_DATA:
                return "data";
            case AVMEDIA_TYPE_ATTACHMENT:
                return "attachment";
            default:
                return "other";
        }
    }

    void AppendProtocols(std::ostringstream &oss, const char *title, int output) {
        void *opaque = nullptr;
        const char *name = nullptr;
        int count = 0;

        oss << title << ":\n";
        while ((name = avio_enum_protocols(&opaque, output)) != nullptr) {
            oss << "  " << name << '\n';
            ++count;
        }
        oss << "count=" << count << "\n\n";
    }

    void AppendFormats(std::ostringstream &oss, const char *title, bool output) {
        void *opaque = nullptr;
        int count = 0;

        oss << title << ":\n";
        if (output) {
            const AVOutputFormat *format = nullptr;
            while ((format = av_muxer_iterate(&opaque)) != nullptr) {
                oss << "  " << format->name;
                if (format->long_name != nullptr) {
                    oss << " - " << format->long_name;
                }
                oss << '\n';
                ++count;
            }
        } else {
            const AVInputFormat *format = nullptr;
            while ((format = av_demuxer_iterate(&opaque)) != nullptr) {
                oss << "  " << format->name;
                if (format->long_name != nullptr) {
                    oss << " - " << format->long_name;
                }
                oss << '\n';
                ++count;
            }
        }
        oss << "count=" << count << "\n\n";
    }

    void AppendCodecs(std::ostringstream &oss, const char *title, bool encoder, AVMediaType mediaType) {
        void *opaque = nullptr;
        const AVCodec *codec = nullptr;
        int count = 0;

        oss << title << " (" << MediaTypeName(mediaType) << "):\n";
        while ((codec = av_codec_iterate(&opaque)) != nullptr) {
            bool matchesRole = encoder ? av_codec_is_encoder(codec) != 0 : av_codec_is_decoder(codec) != 0;
            if (!matchesRole || codec->type != mediaType) {
                continue;
            }

            oss << "  " << codec->name;
            if (codec->long_name != nullptr) {
                oss << " - " << codec->long_name;
            }
            oss << '\n';
            ++count;
        }
        oss << "count=" << count << "\n\n";
    }

    std::string BuildFFmpegInfo() {
        std::ostringstream oss;

        oss << "FFmpeg Runtime Info\n"
            << "===================\n\n";

        oss << "libavutil\n"
            << "  version: " << av_version_info() << '\n'
            << "  version_int: " << VersionToString(avutil_version()) << '\n'
            << "  license: " << avutil_license() << "\n\n";

        oss << "libavcodec\n"
            << "  version_int: " << VersionToString(avcodec_version()) << '\n'
            << "  license: " << avcodec_license() << "\n\n";

        oss << "libavformat\n"
            << "  version_int: " << VersionToString(avformat_version()) << '\n'
            << "  license: " << avformat_license() << "\n\n";

        oss << "configuration\n"
            << "  avutil: " << avutil_configuration() << "\n"
            << "  avcodec: " << avcodec_configuration() << "\n"
            << "  avformat: " << avformat_configuration() << "\n\n";

        AppendProtocols(oss, "input protocols", 0);
        AppendProtocols(oss, "output protocols", 1);
        AppendFormats(oss, "demuxers", false);
        AppendFormats(oss, "muxers", true);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_VIDEO);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_AUDIO);
        AppendCodecs(oss, "decoders", false, AVMEDIA_TYPE_SUBTITLE);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_VIDEO);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_AUDIO);
        AppendCodecs(oss, "encoders", true, AVMEDIA_TYPE_SUBTITLE);

        return oss.str();
    }

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_makeNativeInstance(JNIEnv *env, jobject obj,
                                                                         jobject instance) {

    return 0;
}



extern "C"
JNIEXPORT jstring JNICALL
Java_io_ffmpegtutotial_player_internal_NativeInstance_getInfo(JNIEnv *env, jobject obj,jlong nativeHandle) {
    std::string info = BuildFFmpegInfo();
    return env->NewStringUTF(info.c_str());
}





