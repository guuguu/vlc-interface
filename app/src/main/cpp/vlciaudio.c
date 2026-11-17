/*
 * vlc interface — app-owned audio output.
 *
 * libVLC hands us decoded stereo 16-bit PCM (the only format its amem output accepts) (libvlc_audio_set_callbacks). We apply
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
#include <stdint.h>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/resource.h>
#include <errno.h>

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
static unsigned g_rate = 48000;   /* rate the device plays at */
/* Rate libVLC sends (agreed in setup). When the output reopens by itself (headphones, Bluetooth, device switch)
   Android may give another rate: the sound is then converted here, so it never plays faster/slower or off pitch. */
static unsigned g_src = 0;
static double g_rsPh = 0.0;          /* resampler: next output position, in input frames */
static float g_rsHist[3 * 2];        /* resampler: last 3 input frames (L,R) */
static unsigned g_rsFrom = 0, g_rsTo = 0;
static atomic_int g_device;        /* output device chosen in the app (0 = Android's default routing) */
static atomic_bool g_disconnected;
static bool g_started;

/* Gains (linear) set from Kotlin; volume/mute from libVLC. */
static _Atomic float g_gainL = 1.f, g_gainR = 1.f, g_vol = 1.f;
/* App volume: used while the app plays on a device Android isn't routing media to (its volume buttons then
   change the other device), so the app's own volume control works on the device actually playing. */
static _Atomic float g_appVol = 1.f;
static atomic_bool g_mute, g_limit;
static bool g_eqRateChanged;       /* output sample rate changed: tone filters are remade */

/* Peaks per written chunk, keyed by play time, read by the UI when that time comes. */
#define RING 256
typedef struct { int64_t pts; float l, r; } peak_t;
static peak_t g_ring[RING];

/* Diagnostics, read by Kotlin (watchdog + Settings → Sound). */
static atomic_long st_setup, st_setupRes = -99, st_play, st_frames, st_lastWrite, st_startRes = -99, st_ptsDelta, st_rate, st_open = -99;
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

/* Music: the largest output buffer the phone allows, so Bluetooth (and a busy phone) has plenty of sound queued
 * and never runs dry. The extra delay is measured (latency_us), so timing and meters stay right. */
static atomic_bool g_deep = false;

static int32_t g_defaultBuf = 0;   /* the stream's own buffer size when opened (used for video) */

static void apply_buffer(void) {
    if (!g_stream) return;
    int32_t cap = AAudioStream_getBufferCapacityInFrames(g_stream);
    int32_t want = atomic_load(&g_deep) ? cap : g_defaultBuf;
    if (want > 0 && want <= cap) AAudioStream_setBufferSizeInFrames(g_stream, want);
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
    /* Always ask for the song's rate; Android may still give another one (then converted in cb_play). */
    AAudioStreamBuilder_setSampleRate(b, (int32_t)(g_src ? g_src : g_rate));
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setErrorCallback(b, on_error, NULL);
    int dev = atomic_load(&g_device);
    if (dev > 0) AAudioStreamBuilder_setDeviceId(b, dev);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &g_stream);
    if (r != AAUDIO_OK && dev > 0) {
        /* The chosen device is gone: fall back to Android's default output. */
        AAudioStreamBuilder_setDeviceId(b, AAUDIO_UNSPECIFIED);
        r = AAudioStreamBuilder_openStream(b, &g_stream);
    }
    AAudioStreamBuilder_delete(b);
    atomic_store(&st_open, r);
    if (r != AAUDIO_OK) { g_stream = NULL; LOGW("open failed %d", r); return false; }
    g_rate = (unsigned)AAudioStream_getSampleRate(g_stream);
    g_eqRateChanged = true;
    atomic_store(&g_disconnected, false);
    g_defaultBuf = AAudioStream_getBufferSizeInFrames(g_stream);
    apply_buffer();
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

/* Make sure the device stream is really running (AAudio refuses start in some transient states). */
static void ensure_started(void) {
    if (!g_stream) return;
    aaudio_stream_state_t st = AAudioStream_getState(g_stream);
    if (st == AAUDIO_STREAM_STATE_STARTED || st == AAUDIO_STREAM_STATE_STARTING) { g_started = true; return; }
    if (st == AAUDIO_STREAM_STATE_PAUSING || st == AAUDIO_STREAM_STATE_FLUSHING || st == AAUDIO_STREAM_STATE_STOPPING) {
        aaudio_stream_state_t next = st;
        AAudioStream_waitForStateChange(g_stream, st, &next, 200LL * 1000000);
    }
    aaudio_result_t r = AAudioStream_requestStart(g_stream);
    atomic_store(&st_startRes, r);
    if (r != AAUDIO_OK) {
        /* Stream unusable: reopen it and try once more. */
        if (open_stream()) { r = AAudioStream_requestStart(g_stream); atomic_store(&st_startRes, r); }
    }
    g_started = (r == AAUDIO_OK);
}

/*
 * Peak limiter (both channels together): does nothing at all unless a sample would go over full scale (-0.01 dBFS).
 * Then the level is lowered just enough at once and comes back smoothly (150 ms) — no waveshaping, so no added
 * distortion; untouched sound passes bit for bit. (It used to bend every peak above -0.5 dBFS whenever a boost was
 * on, which coloured loud passages.)
 */
static float g_lg = 1.f;
static inline void limit2(float *l, float *r, float rel) {
    float m = fmaxf(fabsf(*l), fabsf(*r));
    if (m * g_lg > 0.999f) g_lg = 0.999f / m;
    else if (g_lg < 1.f) { g_lg += (1.f - g_lg) * rel; if (g_lg > 0.99999f) g_lg = 1.f; }
    if (g_lg != 1.f) { *l *= g_lg; *r *= g_lg; }
}

/* ---------- Tone (loudness / treble) equalizer, done here so it changes in the same place and at the same moment
 * as the level: 10 peaking filters at libVLC's band frequencies; gains glide to their new values buffer by buffer
 * and the overall level ramps inside each buffer, so nothing jumps or clicks. ---------- */
#define NB 10
static const float EQ_F[NB] = { 60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000 };
static const float EQ_Q[NB] = { 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 1.8f, 2.4f, 2.4f };
static _Atomic float g_eqTarget[NB];          /* dB, set from Kotlin */
static float g_eqCur[NB];                     /* dB, gliding toward the target */
static float g_eqUsed[NB] = { 99 };           /* dB the coefficients were made for */
typedef struct { float b0, b1, b2, a1, a2; } bq_t;
static bq_t g_bq[NB];
static float g_z[NB][2][2];                   /* filter state per band, per channel */
static bool g_eqActive;
static float g_prevL = 1.f, g_prevR = 1.f;    /* overall gains of the previous buffer (ramp start) */

static void eq_design(int b, float db) {
    float fs = (float)(g_src ? g_src : g_rate), f = EQ_F[b];   /* the EQ works on the song's samples */
    if (f > fs * 0.45f) f = fs * 0.45f;
    float A = powf(10.f, db / 40.f), w0 = 2.f * (float)M_PI * f / fs, cw = cosf(w0), al = sinf(w0) / (2.f * EQ_Q[b]);
    float a0 = 1.f + al / A;
    g_bq[b].b0 = (1.f + al * A) / a0; g_bq[b].b1 = -2.f * cw / a0; g_bq[b].b2 = (1.f - al * A) / a0;
    g_bq[b].a1 = -2.f * cw / a0; g_bq[b].a2 = (1.f - al / A) / a0;
}

/* Once per buffer: glide the band gains toward their targets and remake the filters that changed. */
static void eq_update(void) {
    bool any = false;
    if (g_eqRateChanged) { g_eqRateChanged = false; for (int b = 0; b < NB; b++) g_eqUsed[b] = 99; memset(g_z, 0, sizeof g_z); }
    for (int b = 0; b < NB; b++) {
        float t = atomic_load(&g_eqTarget[b]);
        float c = g_eqCur[b] + (t - g_eqCur[b]) * 0.5f;
        if (fabsf(t - c) < 0.02f) c = t;
        g_eqCur[b] = c;
        if (fabsf(c - g_eqUsed[b]) > 0.001f) { eq_design(b, c); g_eqUsed[b] = c; }
        if (fabsf(c) > 0.001f) any = true;
    }
    if (!any && g_eqActive) memset(g_z, 0, sizeof g_z);
    g_eqActive = any;
}

static inline float eq_run(float x, int ch) {
    for (int b = 0; b < NB; b++) {
        if (fabsf(g_eqCur[b]) < 0.001f) continue;
        bq_t *q = &g_bq[b]; float *z = g_z[b][ch];
        float y = q->b0 * x + z[0];
        z[0] = q->b1 * x - q->a1 * y + z[1];
        z[1] = q->b2 * x - q->a2 * y;
        x = y;
    }
    return x;
}

/*
 * Rate conversion (only when the device reopened at another rate than libVLC sends): cubic Hermite interpolation
 * with 3 frames kept between buffers, so blocks join without clicks. in: n frames (L,R) at g_src;
 * out: frames at g_rate. Returns the number of output frames.
 */
static float g_rsExt[(4096 + 3) * 2];
static float g_rsOut[(4096 * 24 + 64) * 2];
static unsigned resample(const float *in, unsigned n) {
    if (g_rsFrom != g_src || g_rsTo != g_rate) {      /* new pair of rates: start clean */
        g_rsFrom = g_src; g_rsTo = g_rate; g_rsPh = 0.0;
        memset(g_rsHist, 0, sizeof g_rsHist);
    }
    double step = (double)g_src / (double)g_rate;
    memcpy(g_rsExt, g_rsHist, sizeof g_rsHist);
    memcpy(g_rsExt + 6, in, (size_t)n * 2 * sizeof(float));
    unsigned o = 0, cap = sizeof g_rsOut / sizeof(float) / 2;
    /* ext index of input frame k is k + 3 (frames -3..-1 are the history). */
    while (o < cap) {
        long i = (long)floor(g_rsPh);
        if (i > (long)n - 3) break;                       /* needs frames i-1 .. i+2 */
        float t = (float)(g_rsPh - (double)i);
        for (int c = 0; c < 2; c++) {
            float y0 = g_rsExt[(i + 2) * 2 + c], y1 = g_rsExt[(i + 3) * 2 + c];
            float y2 = g_rsExt[(i + 4) * 2 + c], y3 = g_rsExt[(i + 5) * 2 + c];
            float a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
            float b = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
            float cc = -0.5f * y0 + 0.5f * y2;
            g_rsOut[o * 2 + c] = ((a * t + b) * t + cc) * t + y1;
        }
        o++; g_rsPh += step;
    }
    g_rsPh -= (double)n;
    /* Keep the last 3 input frames for the next block. */
    if (n >= 3) memcpy(g_rsHist, in + (n - 3) * 2, sizeof g_rsHist);
    else { memmove(g_rsHist, g_rsHist + n * 2, (3 - n) * 2 * sizeof(float)); memcpy(g_rsHist + (3 - n) * 2, in, n * 2 * sizeof(float)); }
    return o;
}

/* libVLC's audio thread feeding our output: raised once to Android's "urgent audio" priority (-19), the level
 * the system's own audio threads use, so other work on the phone doesn't make the sound stutter. */
static __thread int t_prio_done = 0;

static void cb_play(void *d, const void *samples, unsigned count, int64_t pts) {
    (void)d;
    if (!t_prio_done) {
        t_prio_done = 1;
        int r = setpriority(PRIO_PROCESS, (id_t)gettid(), -19);
        /* Tiny numbers (filter tails in quiet passages) flushed to zero: they are inaudible but very slow on the
           CPU, and could make the sound stutter. */
#if defined(__aarch64__)
        { uint64_t fpcr; __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr)); fpcr |= (1ULL << 24); __asm__ volatile("msr fpcr, %0" :: "r"(fpcr)); }
#elif defined(__arm__)
        { uint32_t fpscr; __asm__ volatile("vmrs %0, fpscr" : "=r"(fpscr)); fpscr |= (1u << 24); __asm__ volatile("vmsr fpscr, %0" :: "r"(fpscr)); }
#elif defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(_mm_getcsr() | 0x8040);
#endif
        __android_log_print(ANDROID_LOG_INFO, "vlciaudio", "audio thread priority -19: %s", r == 0 ? "ok" : strerror(errno));
    }
    pthread_mutex_lock(&g_lock);
    if (atomic_load(&g_disconnected) || !g_stream) {
        /* Output changed (headphones, Bluetooth…): reopen on the new device. */
        open_stream();
    }
    atomic_fetch_add(&st_play, 1);
    if (!g_stream) { pthread_mutex_unlock(&g_lock); usleep((useconds_t)((int64_t)count * 1000000 / (g_src ? g_src : g_rate))); return; }
    if (!g_started) ensure_started();

    /* Wait until it is time to hand this buffer to the device (keeps lip sync).
       A delay that makes no sense (clock mismatch) is ignored rather than stalling the sound. */
    int64_t delta = pts - now_us();
    atomic_store(&st_ptsDelta, delta);
    int64_t wait = delta - latency_us();
    if (wait > 2000 && wait < 3000000) {
        pthread_mutex_unlock(&g_lock);
        usleep((useconds_t)(wait > 500000 ? 500000 : wait));
        pthread_mutex_lock(&g_lock);
        if (!g_stream) { pthread_mutex_unlock(&g_lock); return; }
        if (!g_started) ensure_started();
    }

    float gl = atomic_load(&g_gainL), gr = atomic_load(&g_gainR);
    float v = atomic_load(&g_mute) ? 0.f : atomic_load(&g_vol) * atomic_load(&g_appVol);
    gl *= v; gr *= v;
    eq_update();
    /* New stream (song start): the level starts where it should, not ramped from the last song's. */
    if (g_prevL < 0.f) { g_prevL = gl; g_prevR = gr; }
    const float rel = 1.f - expf(-1.f / (0.15f * (float)(g_src ? g_src : g_rate)));
    /* The level ramps from the last buffer's value to this one's over the buffer (no step). */
    float sl = g_prevL, sr = g_prevR;
    float dl = count ? (gl - sl) / (float)count : 0.f, dr = count ? (gr - sr) / (float)count : 0.f;
    g_prevL = gl; g_prevR = gr;

    static float buf[4096 * 2];
    const int16_t *in = (const int16_t *)samples;
    const float k16 = 1.f / 32768.f;
    unsigned done = 0;
    float pl = 0.f, pr = 0.f;
    while (done < count) {
        unsigned n = count - done; if (n > 4096) n = 4096;
        for (unsigned i = 0; i < n; i++) {
            float x = in[(done + i) * 2] * k16, y = in[(done + i) * 2 + 1] * k16;
            if (g_eqActive) { x = eq_run(x, 0); y = eq_run(y, 1); }
            float k = (float)(done + i);
            float l = x * (sl + dl * k), r = y * (sr + dr * k);
            limit2(&l, &r, rel);                              /* only acts on overs */
            buf[i * 2] = l; buf[i * 2 + 1] = r;
            float al = fabsf(l), ar = fabsf(r);
            if (al > pl) pl = al;
            if (ar > pr) pr = ar;
        }
        const float *outp = buf; int32_t outN = (int32_t)n;
        if (g_src && g_src != g_rate) { outN = (int32_t)resample(buf, n); outp = g_rsOut; }
        int32_t w = outN > 0 ? AAudioStream_write(g_stream, outp, outN, 1000000000LL) : 0;
        atomic_store(&st_lastWrite, w);
        if (w < 0) { atomic_store(&g_disconnected, true); break; }
        if (w == 0 && !g_started) ensure_started();
        atomic_fetch_add(&st_frames, w);
        done += n;
    }
    unsigned wi = atomic_load(&g_w);
    int64_t when = (delta > -3000000 && delta < 3000000) ? pts : now_us() + latency_us();
    g_ring[wi % RING] = (peak_t){ when, pl > 1.f ? 1.f : pl, pr > 1.f ? 1.f : pr };
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
    if (g_stream && !g_started) ensure_started();
    pthread_mutex_unlock(&g_lock);
}

static void cb_flush(void *d, int64_t pts) {
    (void)d; (void)pts;
    pthread_mutex_lock(&g_lock);
    if (g_stream) {
        aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
        AAudioStream_requestPause(g_stream);
        AAudioStream_waitForStateChange(g_stream, AAUDIO_STREAM_STATE_PAUSING, &next, 200LL * 1000000);
        AAudioStream_requestFlush(g_stream);
        AAudioStream_waitForStateChange(g_stream, AAUDIO_STREAM_STATE_FLUSHING, &next, 200LL * 1000000);
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
    atomic_fetch_add(&st_setup, 1);
    /* libVLC 3's amem output only accepts S16N (any other format is refused after setup, then retried forever). */
    memcpy(format, "S16N", 4);
    *channels = 2;
    if (*rate < 8000 || *rate > 192000) *rate = 48000;
    pthread_mutex_lock(&g_lock);
    g_prevL = g_prevR = -1.f;                          /* level starts right (see cb_play) */
    g_lg = 1.f;
    if (!g_stream || g_src != *rate) {
        g_src = *rate;
        if (!open_stream()) {
            /* Some devices refuse the file's rate: try the usual 48 kHz and let libVLC resample. */
            g_src = 48000;
            if (!open_stream()) { pthread_mutex_unlock(&g_lock); atomic_store(&st_setupRes, -1); return -1; }
        }
        *rate = g_rate; /* device may have picked another rate: libVLC resamples to it */
        g_src = g_rate;
        g_eqRateChanged = true;
    }
    atomic_store(&st_rate, g_rate);
    pthread_mutex_unlock(&g_lock);
    atomic_store(&st_setupRes, 0);
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

/* Level and tone together (one call, so both change in the same buffer): gains linear, eq = 10 band gains in dB. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setGainsEq(JNIEnv *env, jobject thiz, jfloat l, jfloat r, jboolean lim, jfloatArray eq) {
    (void)thiz;
    jfloat g[NB] = { 0 };
    if (eq && (*env)->GetArrayLength(env, eq) >= NB) (*env)->GetFloatArrayRegion(env, eq, 0, NB, g);
    for (int b = 0; b < NB; b++) atomic_store(&g_eqTarget[b], g[b]);
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

/* [setup calls, setup result, play calls, frames written, last write, start result, frames heard, stream state, pts-now µs, rate, open result, volume×1000, mute] */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_stats(JNIEnv *env, jobject thiz, jlongArray out) {
    (void)thiz;
    jlong v[13];
    pthread_mutex_lock(&g_lock);
    v[6] = g_stream ? AAudioStream_getFramesRead(g_stream) : -1;
    v[7] = g_stream ? AAudioStream_getState(g_stream) : -1;
    pthread_mutex_unlock(&g_lock);
    v[0] = atomic_load(&st_setup); v[1] = atomic_load(&st_setupRes); v[2] = atomic_load(&st_play);
    v[3] = atomic_load(&st_frames); v[4] = atomic_load(&st_lastWrite); v[5] = atomic_load(&st_startRes);
    v[8] = atomic_load(&st_ptsDelta); v[9] = atomic_load(&st_rate); v[10] = atomic_load(&st_open);
    v[11] = (jlong)(atomic_load(&g_vol) * 1000.f); v[12] = atomic_load(&g_mute);
    (*env)->SetLongArrayRegion(env, out, 0, 13, v);
}

/* Picture hue/saturation, live (libVLC "adjust" filter). Returns 0 when applied. */
typedef void (*f_adj_int)(void *, unsigned, int);
typedef void (*f_adj_float)(void *, unsigned, float);
JNIEXPORT jint JNICALL
Java_app_vlcinterface_player_NativeAudio_adjust(JNIEnv *env, jobject thiz, jlong libObj, jlong mpObj, jboolean enable, jfloat hue, jfloat sat,
                                                jfloat contrast, jfloat brightness, jfloat gamma) {
    (void)env; (void)thiz;
    static f_adj_int p_int; static f_adj_float p_float;
    if (!p_int) p_int = (f_adj_int)find("libvlc_video_set_adjust_int");
    if (!p_float) p_float = (f_adj_float)find("libvlc_video_set_adjust_float");
    if (!p_int || !p_float) return -1;
    void *mp = resolve_player(libObj, mpObj);
    if (!mp) return -2;
    /* libvlc_adjust_Enable 0, Contrast 1, Brightness 2, Hue 3, Saturation 4, Gamma 5 */
    p_float(mp, 1, contrast);
    p_float(mp, 2, brightness);
    p_float(mp, 3, hue);
    p_float(mp, 4, sat);
    p_float(mp, 5, gamma);
    p_int(mp, 0, enable ? 1 : 0);
    return 0;
}

/* The phone's output changed (e.g. speaker → Bluetooth chosen in Android's output switcher): Android doesn't
   always move an open stream, so reopen it on the device now in use (done on the next block of sound). */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_reroute(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    atomic_store(&g_disconnected, true);
}

/* Deep buffer on/off (music on / video off), applied to the open stream at once. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setDeep(JNIEnv *env, jobject thiz, jboolean on) {
    (void)env; (void)thiz;
    atomic_store(&g_deep, on ? true : false);
    pthread_mutex_lock(&g_lock);
    apply_buffer();
    pthread_mutex_unlock(&g_lock);
}

/* Plays on this output device (AudioDeviceInfo id), or Android's default routing with 0. Takes effect at once. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setDevice(JNIEnv *env, jobject thiz, jint id) {
    (void)env; (void)thiz;
    atomic_store(&g_device, id);
    /* Switch right now, also in the middle of a song: reopen the stream on the new device and keep playing.
       (The next block of sound then goes to the new device; nothing is paused or restarted.) */
    pthread_mutex_lock(&g_lock);
    if (g_stream) {
        bool wasPlaying = g_started;
        if (open_stream() && wasPlaying) ensure_started();
    } else atomic_store(&g_disconnected, true);
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setAppVolume(JNIEnv *env, jobject thiz, jfloat v) {
    (void)env; (void)thiz;
    atomic_store(&g_appVol, v < 0.f ? 0.f : (v > 2.f ? 2.f : v));
}
