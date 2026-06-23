# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("ffmpegavtutorial")



set(FFMPEG_ROOT ${CMAKE_SOURCE_DIR})
set(FFMPEG_INCLUDE_CURRENT ${FFMPEG_ROOT}/lib/ffmpeg/include)
set(FFMPEG_LIB_DIR ${FFMPEG_ROOT}/lib)
set(OPENSSL_ROOT ${CMAKE_SOURCE_DIR})
set(OPENSSL_INCLUDE_DIR ${OPENSSL_ROOT}/lib/openssl/include)
set(OPENSSL_LIB_DIR ${OPENSSL_ROOT}/lib)

set(X264_ROOT ${CMAKE_SOURCE_DIR})
set(X264_INCLUDE_DIR ${X264_ROOT}/lib/x264/include)
set(X264_LIB_DIR ${X264_ROOT}/lib)

# Use only the new FFmpeg headers to avoid mixing old and new APIs.
include_directories(
        ${FFMPEG_INCLUDE_CURRENT}
        ${FFMPEG_ROOT}
        ${OPENSSL_INCLUDE_DIR}
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
set_target_properties(x264 PROPERTIES IMPORTED_LOCATION ${OPENSSL_LIB_DIR}/x264/${ANDROID_ABI}/libx264.a)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp
        AVEngine.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
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
