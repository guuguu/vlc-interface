/*
 * vlc interface — app-owned audio output.
 *
 * libVLC hands us decoded stereo float PCM (libvlc_audio_set_callbacks). We apply
 * balance + preamp + volume ourselves, measure the left/right peaks for the VU meters,
 * and play it through AAudio, timed to libVLC's clock so video stays in sync.
 *
 * libVLC functions are looked up at run time (no headers needed), and the native
 * media player pointer is taken from the Java objects after a layout cross-check.
 * If anything does not match, attach() fails and the app keeps libVLC's own output.
 */
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "vlciaudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

typedef void (*play_cb)(void *, const void *, unsigned, int64_t);
typedef void (*ts_cb)(void *, int64_t);
typedef void (*drain_cb)(void *);
typedef void (*vol_cb)(void *, float, bool);
typedef int (*setup_cb)(void **, char *, unsigned *, unsigned *);
typedef void (*cleanup_cb)(void *);

typedef void (*f_set_callbacks)(void *, play_cb, ts_cb, ts_cb, ts_cb, drain_cb, void *);
typedef void (*f_set_volume_cb)(void *, vol_cb);
typedef void (*f_set_format_cbs)(void *, setup_cb, cleanup_cb);
typedef int64_t (*f_clock)(void);

static f_set_callbacks p_set_callbacks;
static f_set_volume_cb p_set_volume_cb;
static f_set_format_cbs p_set_format_cbs;
static f_clock p_clock;

static void *g_mp;                 /* libvlc_media_player_t* we are attached to */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *g_stream;
static unsigned g_rate = 48000;
static atomic_bool g_disconnected;
static bool g_started;

/* Gains (linear) set from Kotlin; volume/mute from libVLC. */
static _Atomic float g_gainL = 1.f, g_gainR = 1.f, g_vol = 1.f;
static atomic_bool g_mute, g_limit;

/* Peaks per written chunk, keyed by play time, read by the UI when that time comes. */
#define RING 256
typedef struct { int64_t pts; float l, r; } peak_t;
static peak_t g_ring[RING];
static atomic_uint g_w, g_r;

static int64_t now_us(void) {
    if (p_clock) return p_clock();
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static void *find(const char *name) {
    static const char *libs[] = { "libvlc.so", "libvlcjni.so", NULL };
    for (int i = 0; libs[i]; i++) {
        void *h = dlopen(libs[i], RTLD_NOW | RTLD_NOLOAD);
        if (!h) continue;
        void *s = dlsym(h, name);
        if (s) return s;
    }
    return dlsym(RTLD_DEFAULT, name);
}

static void on_error(AAudioStream *s, void *u, aaudio_result_t err) {
    (void)s; (void)u;
    LOGW("stream error %d", err);
    atomic_store(&g_disconnected, true);
}

static void close_stream(void) {
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = NULL;
    }
    g_started = false;
}

static bool open_stream(void) {
    close_stream();
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)g_rate);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setErrorCallback(b, on_error, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &g_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { g_stream = NULL; LOGW("open failed %d", r); return false; }
    g_rate = (unsigned)AAudioStream_getSampleRate(g_stream);
    atomic_store(&g_disconnected, false);
    return true;
}

/* How long until audio written now is heard, in microseconds. */
static int64_t latency_us(void) {
    if (!g_stream) return 0;
    int64_t pos = 0, t = 0;
    int64_t written = AAudioStream_getFramesWritten(g_stream);
    if (AAudioStream_getTimestamp(g_stream, CLOCK_MONOTONIC, &pos, &t) == AAUDIO_OK) {
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t nowNs = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        int64_t heard = pos + (nowNs - t) * (int64_t)g_rate / 1000000000LL;
        int64_t lat = written - heard;
        if (lat >= 0) return lat * 1000000LL / g_rate;
    }
    int64_t buf = AAudioStream_getBufferSizeInFrames(g_stream);
    return (written > 0 ? buf : 0) * 1000000LL / g_rate;
}

static inline float limit(float x) {
    /* Soft knee above -0.5 dBFS so preamp boosts never clip harshly. */
    const float k = 0.944f;
    float a = fabsf(x);
    if (a <= k) return x;
    float y = k + (1.f - k) * tanhf((a - k) / (1.f - k));
    return x < 0 ? -y : y;
}

static void cb_play(void *d, const void *samples, unsigned count, int64_t pts) {
    (void)d;
    pthread_mutex_lock(&g_lock);
    if (atomic_load(&g_disconnected) || !g_stream) {
        /* Output changed (headphones, Bluetooth…): reopen on the new device. */
        open_stream();
    }
    if (!g_stream) { pthread_mutex_unlock(&g_lock); usleep((useconds_t)((int64_t)count * 1000000 / g_rate)); return; }
    if (!g_started) { AAudioStream_requestStart(g_stream); g_started = true; }

    /* Wait until it is time to hand this buffer to the device (keeps lip sync). */
    int64_t wait = pts - now_us() - latency_us();
    if (wait > 2000) {
        pthread_mutex_unlock(&g_lock);
        usleep((useconds_t)(wait > 500000 ? 500000 : wait));
        pthread_mutex_lock(&g_lock);
        if (!g_stream) { pthread_mutex_unlock(&g_lock); return; }
        if (!g_started) { AAudioStream_requestStart(g_stream); g_started = true; }
    }

    float gl = atomic_load(&g_gainL), gr = atomic_load(&g_gainR);
    float v = atomic_load(&g_mute) ? 0.f : atomic_load(&g_vol);
    gl *= v; gr *= v;
    bool lim = atomic_load(&g_limit) || gl > 1.f || gr > 1.f;

    static float buf[4096 * 2];
    const float *in = (const float *)samples;
    unsigned done = 0;
    float pl = 0.f, pr = 0.f;
    while (done < count) {
        unsigned n = count - done; if (n > 4096) n = 4096;
        for (unsigned i = 0; i < n; i++) {
            float l = in[(done + i) * 2] * gl, r = in[(done + i) * 2 + 1] * gr;
            if (lim) { l = limit(l); r = limit(r); }
            buf[i * 2] = l; buf[i * 2 + 1] = r;
            float al = fabsf(l), ar = fabsf(r);
            if (al > pl) pl = al;
            if (ar > pr) pr = ar;
        }
        int32_t w = AAudioStream_write(g_stream, buf, (int32_t)n, 1000000000LL);
        if (w < 0) { atomic_store(&g_disconnected, true); break; }
        done += n;
    }
    unsigned wi = atomic_load(&g_w);
    g_ring[wi % RING] = (peak_t){ pts, pl > 1.f ? 1.f : pl, pr > 1.f ? 1.f : pr };
    atomic_store(&g_w, wi + 1);
    pthread_mutex_unlock(&g_lock);
}

static void cb_pause(void *d, int64_t pts) {
    (void)d; (void)pts;
    pthread_mutex_lock(&g_lock);
    if (g_stream && g_started) { AAudioStream_requestPause(g_stream); g_started = false; }
    pthread_mutex_unlock(&g_lock);
}

static void cb_resume(void *d, int64_t pts) {
    (void)d; (void)pts;
    pthread_mutex_lock(&g_lock);
    if (g_stream && !g_started) { AAudioStream_requestStart(g_stream); g_started = true; }
    pthread_mutex_unlock(&g_lock);
}

static void cb_flush(void *d, int64_t pts) {
    (void)d; (void)pts;
    pthread_mutex_lock(&g_lock);
    if (g_stream) {
        AAudioStream_requestPause(g_stream);
        AAudioStream_requestFlush(g_stream);
        g_started = false;
    }
    atomic_store(&g_r, atomic_load(&g_w));
    pthread_mutex_unlock(&g_lock);
}

static void cb_drain(void *d) {
    (void)d;
    int64_t l;
    pthread_mutex_lock(&g_lock);
    l = latency_us();
    pthread_mutex_unlock(&g_lock);
    if (l > 0) usleep((useconds_t)(l > 2000000 ? 2000000 : l));
}

static void cb_volume(void *d, float vol, bool mute) {
    (void)d;
    atomic_store(&g_vol, vol);
    atomic_store(&g_mute, mute);
}

static int cb_setup(void **opaque, char *format, unsigned *rate, unsigned *channels) {
    (void)opaque;
    memcpy(format, "FL32", 4);
    *channels = 2;
    if (*rate < 8000 || *rate > 192000) *rate = 48000;
    pthread_mutex_lock(&g_lock);
    if (!g_stream || g_rate != *rate) {
        g_rate = *rate;
        if (!open_stream()) { pthread_mutex_unlock(&g_lock); return -1; }
        if (g_rate != *rate) *rate = g_rate; /* device picked another rate: libVLC resamples */
    }
    pthread_mutex_unlock(&g_lock);
    return 0;
}

static void cb_cleanup(void *opaque) {
    (void)opaque;
    pthread_mutex_lock(&g_lock);
    close_stream();
    pthread_mutex_unlock(&g_lock);
}

/*
 * libvlcjni keeps, for each Java VLCObject, a native struct whose first field is the
 * parent libvlc_instance_t* and whose second field is the object's own native pointer.
 * Check that the player's parent equals the LibVLC object's own pointer before using it.
 */
static void *resolve_player(jlong libObj, jlong mpObj) {
    if (!libObj || !mpObj) return NULL;
    void **lib = (void **)(intptr_t)libObj;
    void **mp = (void **)(intptr_t)mpObj;
    void *instance = lib[1];
    if (!instance || mp[0] != instance || !mp[1]) return NULL;
    return mp[1];
}

JNIEXPORT jint JNICALL
Java_app_vlcinterface_player_NativeAudio_attach(JNIEnv *env, jobject thiz, jlong libObj, jlong mpObj) {
    (void)env; (void)thiz;
    p_set_callbacks = (f_set_callbacks)find("libvlc_audio_set_callbacks");
    p_set_volume_cb = (f_set_volume_cb)find("libvlc_audio_set_volume_callback");
    p_set_format_cbs = (f_set_format_cbs)find("libvlc_audio_set_format_callbacks");
    p_clock = (f_clock)find("libvlc_clock");
    if (!p_set_callbacks || !p_set_format_cbs || !p_clock) { LOGW("libvlc symbols not found"); return -1; }
    void *mp = resolve_player(libObj, mpObj);
    if (!mp) { LOGW("player layout check failed"); return -2; }
    g_mp = mp;
    p_set_callbacks(mp, cb_play, cb_pause, cb_resume, cb_flush, cb_drain, NULL);
    if (p_set_volume_cb) p_set_volume_cb(mp, cb_volume);
    p_set_format_cbs(mp, cb_setup, cb_cleanup);
    LOGI("attached");
    return 0;
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_detach(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_mp && p_set_callbacks) p_set_callbacks(g_mp, NULL, NULL, NULL, NULL, NULL, NULL);
    g_mp = NULL;
    pthread_mutex_lock(&g_lock);
    close_stream();
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setGains(JNIEnv *env, jobject thiz, jfloat l, jfloat r, jboolean lim) {
    (void)env; (void)thiz;
    atomic_store(&g_gainL, l);
    atomic_store(&g_gainR, r);
    atomic_store(&g_limit, lim);
}

/* Peaks (0..1) of the audio being heard right now: out[0] = left, out[1] = right. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_readPeaks(JNIEnv *env, jobject thiz, jfloatArray out) {
    (void)thiz;
    float pl = 0.f, pr = 0.f;
    int64_t now = now_us();
    unsigned r = atomic_load(&g_r), w = atomic_load(&g_w);
    if (w - r > RING) r = w - RING;
    while (r != w && g_ring[r % RING].pts <= now) {
        if (g_ring[r % RING].l > pl) pl = g_ring[r % RING].l;
        if (g_ring[r % RING].r > pr) pr = g_ring[r % RING].r;
        r++;
    }
    atomic_store(&g_r, r);
    jfloat v[2] = { pl, pr };
    (*env)->SetFloatArrayRegion(env, out, 0, 2, v);
}
