cmake_minimum_required(VERSION 3.22.1)
project(vlciaudio C)
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
