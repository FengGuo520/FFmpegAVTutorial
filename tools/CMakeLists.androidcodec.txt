cmake_minimum_required(VERSION 3.22.1)

project("codec")

set(FFMPEG_ROOT ${CMAKE_SOURCE_DIR})
set(FFMPEG_INCLUDE_CURRENT ${FFMPEG_ROOT}/lib/ffmpeg/include)
set(FFMPEG_LIB_DIR ${FFMPEG_ROOT}/lib)
set(OPENSSL_ROOT ${CMAKE_SOURCE_DIR})
set(OPENSSL_INCLUDE_DIR ${OPENSSL_ROOT}/lib/openssl/include)
set(OPENSSL_LIB_DIR ${OPENSSL_ROOT}/lib)

set(X264_ROOT ${CMAKE_SOURCE_DIR})
set(X264_INCLUDE_DIR ${X264_ROOT}/lib/x264/include)
set(X264_LIB_DIR ${X264_ROOT}/lib)

include_directories(
        ${FFMPEG_INCLUDE_CURRENT}
        ${FFMPEG_ROOT}
        ${OPENSSL_INCLUDE_DIR}
        ${X264_INCLUDE_DIR}
)

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/ffmpeg/${ANDROID_ABI}/libavcodec.a)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/ffmpeg/${ANDROID_ABI}/libavformat.a)

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/ffmpeg/${ANDROID_ABI}/libavutil.a)

add_library(swscale STATIC IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/ffmpeg/${ANDROID_ABI}/libswscale.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/ffmpeg/${ANDROID_ABI}/libswresample.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${OPENSSL_LIB_DIR}/openssl/${ANDROID_ABI}/libssl.a)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${OPENSSL_LIB_DIR}/openssl/${ANDROID_ABI}/libcrypto.a)

add_library(x264 STATIC IMPORTED)
set_target_properties(x264 PROPERTIES IMPORTED_LOCATION ${X264_LIB_DIR}/x264/${ANDROID_ABI}/libx264.a)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        AVEngine.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        mediandk
        z
        x264
        avcodec
        avformat
        avutil
        swscale
        swresample
        ssl
        crypto)
