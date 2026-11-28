/* Identify song (2026-11-28): Chromaprint fingerprint of 16-bit PCM for an AcoustID lookup. */
#include <jni.h>
#include <stdint.h>
#include "chromaprint/src/chromaprint.h"

JNIEXPORT jstring JNICALL
Java_app_vlcinterface_sources_Chroma_fingerprint(JNIEnv *env, jclass cls, jshortArray pcm, jint count, jint rate, jint channels) {
    ChromaprintContext *c = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!c) return NULL;
    jstring out = NULL;
    if (chromaprint_start(c, rate, channels)) {
        jshort *d = (*env)->GetShortArrayElements(env, pcm, NULL);
        int ok = d && chromaprint_feed(c, (const int16_t *) d, count);
        if (d) (*env)->ReleaseShortArrayElements(env, pcm, d, JNI_ABORT);
        char *fp = NULL;
        if (ok && chromaprint_finish(c) && chromaprint_get_fingerprint(c, &fp) && fp) {
            out = (*env)->NewStringUTF(env, fp);
            chromaprint_dealloc(fp);
        }
    }
    chromaprint_free(c);
    return out;
}
