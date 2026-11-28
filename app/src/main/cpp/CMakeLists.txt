cmake_minimum_required(VERSION 3.22.1)
project(vlciaudio C CXX)
add_library(vlciaudio SHARED vlciaudio.c)
target_compile_options(vlciaudio PRIVATE -O2 -Wall)
target_link_libraries(vlciaudio aaudio log dl m)

# LAME 3.100 (LGPL, separate shared library: libmp3lame.so) — MP3 encoding of saved radio songs, with a small JNI
# wrapper (lamejni.c). Licence text in lame/COPYING-LGPL.
file(GLOB LAME_SRC lame/*.c)
add_library(mp3lame SHARED ${LAME_SRC})
target_include_directories(mp3lame PRIVATE lame)
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS HAVE_STDINT_H HAVE_STDLIB_H HAVE_STRING_H HAVE_LIMITS_H
    HAVE_ERRNO_H HAVE_FCNTL_H ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O3 -w -fno-strict-aliasing)
target_link_libraries(mp3lame m)

add_library(lamejni SHARED lamejni.c)
target_include_directories(lamejni PRIVATE lame)
target_link_libraries(lamejni mp3lame log)

# projectM 4 (LGPL 2.1, prebuilt shared library from the "Build libprojectM" workflow: projectm/lib, headers in
# projectm/include, licence in projectm/) and the app's small bridge to it (projectmjni.c).
add_library(projectM-4 SHARED IMPORTED)
set_target_properties(projectM-4 PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/projectm/lib/${ANDROID_ABI}/libprojectM-4.so)
add_library(projectmjni SHARED projectmjni.c)
target_include_directories(projectmjni PRIVATE projectm/include)
target_link_libraries(projectmjni projectM-4 vlciaudio GLESv3 log)

# Chromaprint 1.5.1 (MIT, with LGPL parts from FFmpeg; licence in chromaprint/) with KissFFT (BSD), as its own shared
# library (libchromaprint.so) — audio fingerprints for "Identify song" (AcoustID) — and its JNI bridge (chromaprintjni.c).
file(GLOB CHROMAPRINT_SRC chromaprint/src/*.cpp)
add_library(chromaprint SHARED ${CHROMAPRINT_SRC} chromaprint/src/utils/base64.cpp chromaprint/src/avresample/resample2.c
    chromaprint/src/kissfft/kiss_fft.c chromaprint/src/kissfft/tools/kiss_fftr.c)
target_include_directories(chromaprint PRIVATE chromaprint/src chromaprint/src/kissfft)
target_compile_definitions(chromaprint PRIVATE HAVE_CONFIG_H _USE_MATH_DEFINES __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS CHROMAPRINT_API_EXPORTS)
set_target_properties(chromaprint PROPERTIES CXX_STANDARD 11)
target_compile_options(chromaprint PRIVATE -O2 -w)
target_link_libraries(chromaprint m)
add_library(chromaprintjni SHARED chromaprintjni.c)
target_link_libraries(chromaprintjni chromaprint)
