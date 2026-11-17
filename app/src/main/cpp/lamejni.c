/* Small JNI bridge to LAME (libmp3lame, LGPL): MP3 encoding of saved radio songs, VBR V0. */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "lame.h"

JNIEXPORT jlong JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nInit(JNIEnv *env, jclass c, jint rate, jint channels, jint vbrQuality) {
    (void)env; (void)c;
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, rate);
    lame_set_num_channels(g, channels);
    lame_set_out_samplerate(g, rate);
    lame_set_VBR(g, vbr_default);              /* modern VBR (mtrh) */
    lame_set_VBR_q(g, vbrQuality);             /* 0 = V0, the highest VBR quality */
    lame_set_quality(g, 2);                    /* encoder effort: high */
    lame_set_bWriteVbrTag(g, 1);               /* Xing/LAME header (duration, gapless) */
    lame_set_write_id3tag_automatic(g, 0);     /* tags are written by the app */
    if (lame_init_params(g) < 0) { lame_close(g); return 0; }
    return (jlong)(intptr_t)g;
}

/* pcm: interleaved 16-bit samples; frames: samples per channel. Returns the MP3 bytes made so far. */
JNIEXPORT jbyteArray JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nEncode(JNIEnv *env, jclass c, jlong h, jshortArray pcm, jint frames, jint channels) {
    (void)c;
    lame_global_flags *g = (lame_global_flags *)(intptr_t)h;
    int cap = (int)(1.25 * frames + 7200);
    unsigned char *out = malloc(cap);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    int n = channels == 2 ? lame_encode_buffer_interleaved(g, in, frames, out, cap)
                          : lame_encode_buffer(g, in, in, frames, out, cap);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    if (n < 0) n = 0;
    jbyteArray r = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, r, 0, n, (jbyte *)out);
    free(out);
    return r;
}

JNIEXPORT jbyteArray JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nFlush(JNIEnv *env, jclass c, jlong h) {
    (void)c;
    lame_global_flags *g = (lame_global_flags *)(intptr_t)h;
    unsigned char out[7200];
    int n = lame_encode_flush(g, out, sizeof out);
    if (n < 0) n = 0;
    jbyteArray r = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, r, 0, n, (jbyte *)out);
    return r;
}

/* The finished Xing/LAME header frame: written over the first frame of the file once encoding is done. */
JNIEXPORT jbyteArray JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nTag(JNIEnv *env, jclass c, jlong h) {
    (void)c;
    lame_global_flags *g = (lame_global_flags *)(intptr_t)h;
    unsigned char buf[2880];
    size_t n = lame_get_lametag_frame(g, buf, sizeof buf);
    jbyteArray r = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, r, 0, (jsize)n, (jbyte *)buf);
    return r;
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nClose(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c;
    if (h) lame_close((lame_global_flags *)(intptr_t)h);
}

JNIEXPORT jstring JNICALL
Java_app_vlcinterface_sources_Mp3Encoder_nVersion(JNIEnv *env, jclass c) {
    (void)c;
    return (*env)->NewStringUTF(env, get_lame_version());
}
