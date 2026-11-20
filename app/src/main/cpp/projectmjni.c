/*
 * projectM (MilkDrop presets, libprojectM 4, LGPL 2.1) for the app's visualisation: one instance per drawing surface,
 * fed with the samples being heard now from the app's own sound engine (vlciaudio.c), drawn with OpenGL ES 3.
 * All calls except nEnable come from the surface's GL thread.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <projectM-4/projectM.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "projectM", __VA_ARGS__)

extern void vlci_pcm_enable(int on);
extern int vlci_pcm_read(float *out, int max, unsigned *cursor);

typedef struct { projectm_handle pm; unsigned cursor; float *buf; int max; } inst_t;

JNIEXPORT jlong JNICALL
Java_app_vlcinterface_player_ProjectM_nCreate(JNIEnv *env, jclass cls, jint w, jint h, jstring preset) {
    (void)cls;
    projectm_handle pm = projectm_create();
    if (!pm) { LOG("create failed"); return 0; }
    inst_t *in = calloc(1, sizeof(inst_t));
    in->pm = pm;
    in->max = (int)projectm_pcm_get_max_samples();
    if (in->max <= 0 || in->max > 8192) in->max = 2048;
    in->buf = malloc(sizeof(float) * 2 * in->max);
    projectm_set_window_size(pm, (size_t)w, (size_t)h);
    projectm_set_fps(pm, 60);
    projectm_set_mesh_size(pm, 64, 48);
    projectm_set_aspect_correction(pm, true);
    projectm_set_preset_locked(pm, true);          /* stays on the chosen preset */
    projectm_set_hard_cut_enabled(pm, false);
    projectm_set_beat_sensitivity(pm, 1.0f);
    const char *data = (*env)->GetStringUTFChars(env, preset, NULL);
    if (data) { projectm_load_preset_data(pm, data, false); (*env)->ReleaseStringUTFChars(env, preset, data); }
    return (jlong)(intptr_t)in;
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_ProjectM_nResize(JNIEnv *env, jclass cls, jlong h, jint w, jint hh) {
    (void)env; (void)cls;
    inst_t *in = (inst_t *)(intptr_t)h; if (!in) return;
    projectm_set_window_size(in->pm, (size_t)w, (size_t)hh);
    glViewport(0, 0, w, hh);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_ProjectM_nLoad(JNIEnv *env, jclass cls, jlong h, jstring preset) {
    (void)cls;
    inst_t *in = (inst_t *)(intptr_t)h; if (!in) return;
    const char *data = (*env)->GetStringUTFChars(env, preset, NULL);
    if (data) { projectm_load_preset_data(in->pm, data, true); (*env)->ReleaseStringUTFChars(env, preset, data); }
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_ProjectM_nRender(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    inst_t *in = (inst_t *)(intptr_t)h; if (!in) return;
    int n = vlci_pcm_read(in->buf, in->max, &in->cursor);
    if (n > 0) projectm_pcm_add_float(in->pm, in->buf, (unsigned)n, PROJECTM_STEREO);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    projectm_opengl_render_frame(in->pm);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_ProjectM_nDestroy(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    inst_t *in = (inst_t *)(intptr_t)h; if (!in) return;
    projectm_destroy(in->pm);
    free(in->buf); free(in);
}

/* Sample tap in the sound engine: on while at least one projectM picture is shown (counted on the Kotlin side). */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_ProjectM_nEnable(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    vlci_pcm_enable(on ? 1 : 0);
}
