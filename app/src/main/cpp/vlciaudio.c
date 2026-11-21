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
/* Bluetooth quality mode: the stream is opened at the Bluetooth link's rate (0 = the song's rate). */
static _Atomic int g_prefRate = 0;
static double g_rsPh = 0.0;          /* resampler: next output position, in input frames */
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
typedef struct { int64_t pts; float l, r, ba, bb; } peak_t;
static peak_t g_ring[RING];

/* Diagnostics, read by Kotlin (watchdog + Settings → Sound). */
static atomic_long st_gen;         /* streams opened so far (frames heard restart from 0 with each one) */
static atomic_long st_setup, st_setupRes = -99, st_play, st_frames, st_lastWrite, st_startRes = -99, st_ptsDelta, st_rate, st_open = -99;
static atomic_uint g_w, g_r, g_r2, g_r3;

/* Visualisation: two frequency bands (lower and upper part of the chosen range), measured on the song's samples
   before the volume, so the pictures don't depend on how loud it is played. */
static _Atomic float g_bandLo = 30.f, g_bandHi = 150.f;
static float g_bandUsed[3] = { 0, 0, 0 };     /* lo, hi, rate the filters were made for */
typedef struct { float b0, b1, b2, a1, a2, z0, z1; } vbq_t;
static vbq_t g_vb[4];                          /* band A: hp, lp; band B: hp, lp */

/* Spectrum analyser: SPEC_N log-spaced bands (≈ 40 Hz … 16 kHz), band-pass filters on the song's samples,
   measured only while a spectrum picture is shown. RMS per written chunk, kept in step with the peaks ring. */
#define SPEC_N 32
typedef struct { float b0, b2, a1, a2, z0, z1; } sbp_t;      /* band-pass (b1 = 0) */
static sbp_t g_sb[SPEC_N];
static float g_specUsed = 0.f;
static atomic_bool g_specOn;
static float g_spec[RING * SPEC_N];

static void spec_update(float fs) {
    if (fs == g_specUsed) return;
    g_specUsed = fs;
    for (int i = 0; i < SPEC_N; i++) {
        float f = 40.f * powf(16000.f / 40.f, (i + 0.5f) / SPEC_N);
        if (f > fs * 0.45f) f = fs * 0.45f;
        float w0 = 2.f * (float)M_PI * f / fs, al = sinf(w0) / (2.f * 2.2f), a0 = 1.f + al;
        g_sb[i].b0 = al / a0; g_sb[i].b2 = -al / a0;
        g_sb[i].a1 = -2.f * cosf(w0) / a0; g_sb[i].a2 = (1.f - al) / a0; g_sb[i].z0 = g_sb[i].z1 = 0.f;
    }
}

static inline float sbp_run(sbp_t *q, float x) {
    float y = q->b0 * x + q->z0;
    q->z0 = -q->a1 * y + q->z1;
    q->z1 = q->b2 * x - q->a2 * y;
    return y;
}

/* projectM: the song's samples (stereo, before the volume) kept with their play time, read by the projectM bridge
   (projectmjni.c) for the part being heard now. Filled only while a projectM picture is shown. */
static int64_t now_us(void);
#define PCM_N 65536                                  /* frames (stereo pairs), power of two */
#define PCM_BLK 128
static float g_pcm[PCM_N * 2];
static atomic_uint g_pcmW;                           /* frames written (wraps) */
typedef struct { int64_t pts; unsigned start, count; int rate; } pcmblk_t;
static pcmblk_t g_pcmBlk[PCM_BLK];
static atomic_uint g_pcmBlkW;
static atomic_bool g_pcmOn;

__attribute__((visibility("default"))) void vlci_pcm_enable(int on) { atomic_store(&g_pcmOn, on ? true : false); }

/* Copies the frames heard since [*cursor] (up to max, the newest ones) into out (interleaved stereo); returns frames. */
__attribute__((visibility("default"))) int vlci_pcm_read(float *out, int max, unsigned *cursor) {
    int64_t now = now_us();
    unsigned bw = atomic_load(&g_pcmBlkW);
    unsigned end = *cursor;
    bool found = false;
    for (unsigned k = 0; k < PCM_BLK && k < bw; k++) {           /* newest block already playing */
        pcmblk_t b = g_pcmBlk[(bw - 1 - k) % PCM_BLK];
        if (b.pts > now) continue;
        int64_t el = (now - b.pts) * (int64_t)b.rate / 1000000;
        if (el > (int64_t)b.count) el = b.count;
        end = b.start + (unsigned)el; found = true; break;
    }
    if (!found) return 0;
    unsigned w = atomic_load(&g_pcmW);
    if (w - end > PCM_N) end = w;
    unsigned from = *cursor;
    if (end - from > (unsigned)max || end < from) from = end - (unsigned)max;   /* only the newest */
    int n = 0;
    for (unsigned i = from; i != end && n < max; i++, n++) {
        out[n * 2] = g_pcm[(i % PCM_N) * 2]; out[n * 2 + 1] = g_pcm[(i % PCM_N) * 2 + 1];
    }
    *cursor = end;
    return n;
}

static void vbq_design(vbq_t *q, bool high, float f, float fs) {
    if (f > fs * 0.45f) f = fs * 0.45f;
    if (f < 10.f) f = 10.f;
    float w0 = 2.f * (float)M_PI * f / fs, cw = cosf(w0), al = sinf(w0) / (2.f * 0.7071f), a0 = 1.f + al;
    if (high) { q->b0 = (1.f + cw) / 2.f / a0; q->b1 = -(1.f + cw) / a0; q->b2 = q->b0; }
    else { q->b0 = (1.f - cw) / 2.f / a0; q->b1 = (1.f - cw) / a0; q->b2 = q->b0; }
    q->a1 = -2.f * cw / a0; q->a2 = (1.f - al) / a0; q->z0 = q->z1 = 0.f;
}

static inline float vbq_run(vbq_t *q, float x) {
    float y = q->b0 * x + q->z0;
    q->z0 = q->b1 * x - q->a1 * y + q->z1;
    q->z1 = q->b2 * x - q->a2 * y;
    return y;
}

/* Wide range (2 octaves or more): split at the middle (geometric); narrow: both bands = the whole range. */
static void bands_update(void) {
    float lo = atomic_load(&g_bandLo), hi = atomic_load(&g_bandHi), fs = (float)(g_src ? g_src : g_rate);
    if (lo == g_bandUsed[0] && hi == g_bandUsed[1] && fs == g_bandUsed[2]) return;
    g_bandUsed[0] = lo; g_bandUsed[1] = hi; g_bandUsed[2] = fs;
    float mid = (hi / lo >= 4.f) ? sqrtf(lo * hi) : hi, lo2 = (hi / lo >= 4.f) ? mid : lo;
    vbq_design(&g_vb[0], true, lo, fs); vbq_design(&g_vb[1], false, mid, fs);
    vbq_design(&g_vb[2], true, lo2, fs); vbq_design(&g_vb[3], false, hi, fs);
}

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
    int pref = atomic_load(&g_prefRate);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)(pref > 0 ? (unsigned)pref : (g_src ? g_src : g_rate)));
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    /* Other apps may listen to the app's sound (Shazam's "listen to apps" without the microphone). */
    if (__builtin_available(android 29, *)) AAudioStreamBuilder_setAllowedCapturePolicy(b, AAUDIO_ALLOW_CAPTURE_BY_ALL);
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
    atomic_fetch_add(&st_gen, 1);
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
 * Peak limiter (both channels together), with look-ahead (2026-11-21 audio check). It does nothing unless a sample
 * would go over full scale (-0.01 dBFS); untouched sound passes bit for bit (only 64 frames ≈ 1.4 ms later).
 * The old one lowered the level within a single sample right on the peak — a hard step at every kick drum, measured
 * as distortion ≈ 38 dB under the music whenever Loudness (and its level make-up) pushed the bass over full scale.
 * Now the sound is delayed by LA frames and the gain glides down over those LA frames before the peak arrives
 * (minimum over the window, then its moving average: never above what the peak needs), and comes back smoothly
 * (150 ms).
 */
#define LA 64
static float g_lg = 1.f;
static float g_laSig[LA * 2];               /* delayed samples (L,R) */
static float g_laReq[LA];                   /* gain each sample needs */
static float g_laHold[LA];                  /* minimum of the needs over the window, per sample */
static double g_laSum = LA;                 /* running sum of g_laHold */
static unsigned g_laW;
static void limit_reset(void) {
    memset(g_laSig, 0, sizeof g_laSig);
    for (int i = 0; i < LA; i++) { g_laReq[i] = 1.f; g_laHold[i] = 1.f; }
    g_laSum = LA; g_lg = 1.f; g_laW = 0;
}
static bool g_laReady;
static inline void limit2(float *l, float *r, float rel) {
    if (!g_laReady) { limit_reset(); g_laReady = true; }
    unsigned w = g_laW % LA;
    float m = fmaxf(fabsf(*l), fabsf(*r));
    float req = m > 0.999f ? 0.999f / m : 1.f;
    g_laSig[w * 2] = *l; g_laSig[w * 2 + 1] = *r;
    g_laReq[w] = req;
    float hold = 1.f;
    for (int i = 0; i < LA; i++) if (g_laReq[i] < hold) hold = g_laReq[i];
    g_laSum += (double)hold - (double)g_laHold[w];
    g_laHold[w] = hold;
    g_laW++;
    float att = (float)(g_laSum / LA);
    if (att > 1.f) att = 1.f;
    /* The output sample now is the one that went in LA-1 samples ago; the average covers it (see above). */
    float ol = g_laSig[(g_laW % LA) * 2], orr = g_laSig[(g_laW % LA) * 2 + 1];
    if (att < g_lg) g_lg = att;
    else if (g_lg < 1.f) { g_lg += (att - g_lg) * rel; if (g_lg > 0.99999f && att >= 1.f) g_lg = 1.f; }
    if (g_lg != 1.f) { ol *= g_lg; orr *= g_lg; }
    *l = ol; *r = orr;
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

/*
 * Treble (2026-11-21 21:45): one high-shelf filter (corner 4 kHz, slope 0.6), like an amplifier's treble knob — a smooth
 * rise from about 2 kHz that keeps climbing to 12 kHz and holds above (at +10 dB: 2k +1.8, 4k +5, 8k +8.4, 12k +9.5).
 * The ten bands above stay for loudness. Glides like the bands.
 */
static _Atomic float g_trebleTarget;
/* The band itself, set in Settings → Audio → Treble band (21:50): 0 = high shelf, 1 = peak (bell); frequency; shelf
   slope or peak Q. A change remakes the filter at once. */
static _Atomic int g_trebleType = 0;
static _Atomic float g_trebleHz = 4000.f, g_trebleW = 0.6f;
static _Atomic int g_trebleGen = 1;
static int g_trebleGenUsed = 0;
static float g_trebleCur, g_trebleUsed = 99.f;
static bq_t g_ts;
static float g_tz[2][2];
static bool g_trebleActive;
static void treble_update(void) {
    float t = atomic_load(&g_trebleTarget);
    float c = g_trebleCur + (t - g_trebleCur) * 0.5f;
    if (fabsf(t - c) < 0.02f) c = t;
    g_trebleCur = c;
    if (g_eqRateChanged) memset(g_tz, 0, sizeof g_tz);
    int gen = atomic_load(&g_trebleGen);
    if (g_eqRateChanged || gen != g_trebleGenUsed || fabsf(c - g_trebleUsed) > 0.001f) {
        float fs = (float)(g_src ? g_src : g_rate), f0 = atomic_load(&g_trebleHz), W = atomic_load(&g_trebleW);
        if (f0 > fs * 0.45f) f0 = fs * 0.45f;
        float A = powf(10.f, c / 40.f), w0 = 2.f * (float)M_PI * f0 / fs, cw = cosf(w0);
        if (atomic_load(&g_trebleType) == 1) {
            /* Peak (bell) at f0, width Q = W. */
            float al = sinf(w0) / (2.f * W), a0 = 1.f + al / A;
            g_ts.b0 = (1.f + al * A) / a0; g_ts.b1 = -2.f * cw / a0; g_ts.b2 = (1.f - al * A) / a0;
            g_ts.a1 = -2.f * cw / a0; g_ts.a2 = (1.f - al / A) / a0;
        } else {
            /* High shelf, corner f0, slope S = W. */
            float al = sinf(w0) / 2.f * sqrtf((A + 1.f / A) * (1.f / W - 1.f) + 2.f), sA = 2.f * sqrtf(A) * al;
            float a0 = (A + 1.f) - (A - 1.f) * cw + sA;
            g_ts.b0 = A * ((A + 1.f) + (A - 1.f) * cw + sA) / a0;
            g_ts.b1 = -2.f * A * ((A - 1.f) + (A + 1.f) * cw) / a0;
            g_ts.b2 = A * ((A + 1.f) + (A - 1.f) * cw - sA) / a0;
            g_ts.a1 = 2.f * ((A - 1.f) - (A + 1.f) * cw) / a0;
            g_ts.a2 = ((A + 1.f) - (A - 1.f) * cw - sA) / a0;
        }
        g_trebleUsed = c; g_trebleGenUsed = gen;
    }
    bool on = fabsf(c) > 0.001f;
    if (!on && g_trebleActive) memset(g_tz, 0, sizeof g_tz);
    g_trebleActive = on;
}
static inline float treble_run(float x, int ch) {
    float *z = g_tz[ch];
    float y = g_ts.b0 * x + z[0];
    z[0] = g_ts.b1 * x - g_ts.a1 * y + z[1];
    z[1] = g_ts.b2 * x - g_ts.a2 * y;
    return y;
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
 * Rate conversion (only when the device reopened at another rate than libVLC sends, e.g. Bluetooth connecting or an
 * output switch mid-song — and then for every following song of the same format, the stream is kept).
 * Windowed-sinc (Kaiser, 48 taps, table of 256 phases with interpolation), low-passed below the lower of the two
 * rates. Replaces the cubic interpolation, whose mirror tones were only 12–21 dB under treble at 12–16 kHz
 * (measured 2026-11-21: a harsh, metallic top after a Bluetooth switch). TAPS frames kept between buffers.
 */
#define RS_HALF 24
#define RS_TAPS (2 * RS_HALF)
#define RS_PH 256
static float g_rsK[2 * RS_HALF * RS_PH + 2];          /* kernel over -HALF..+HALF input frames */
static float g_rsH[RS_TAPS * 2];                      /* last TAPS input frames */
static float g_rsExt[(4096 + RS_TAPS) * 2];
static float g_rsOut[(4096 * 24 + 64) * 2];
static double bessel_i0(double x) { double s = 1, t = 1; for (int k = 1; k < 40; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; } return s; }
static void rs_design(void) {
    double c = (g_rate < g_src ? (double)g_rate / g_src : 1.0) * 0.94, beta = 9.0, i0b = bessel_i0(beta);
    for (int i = 0; i <= 2 * RS_HALF * RS_PH + 1; i++) {
        double x = (double)i / RS_PH - RS_HALF;
        double sx = fabs(x) < 1e-9 ? 1.0 : sin(M_PI * c * x) / (M_PI * c * x);
        double u = x / RS_HALF, w = fabs(u) >= 1 ? 0 : bessel_i0(beta * sqrt(1 - u * u)) / i0b;
        g_rsK[i] = (float)(c * sx * w);
    }
}
static inline float rs_k(double x) {                 /* kernel at x (input frames), interpolated */
    double p = (x + RS_HALF) * RS_PH; int i = (int)p; if (i < 0 || i >= 2 * RS_HALF * RS_PH) return 0.f;
    float t = (float)(p - i); return g_rsK[i] + (g_rsK[i + 1] - g_rsK[i]) * t;
}
static unsigned resample(const float *in, unsigned n) {
    if (g_rsFrom != g_src || g_rsTo != g_rate) {      /* new pair of rates: start clean */
        g_rsFrom = g_src; g_rsTo = g_rate; g_rsPh = RS_TAPS - RS_HALF;
        memset(g_rsH, 0, sizeof g_rsH);
        rs_design();
    }
    double step = (double)g_src / (double)g_rate;
    memcpy(g_rsExt, g_rsH, sizeof g_rsH);
    memcpy(g_rsExt + RS_TAPS * 2, in, (size_t)n * 2 * sizeof(float));
    unsigned o = 0, cap = sizeof g_rsOut / sizeof(float) / 2;
    double last = (double)(RS_TAPS + n) - RS_HALF;    /* needs frames floor(p)-HALF+1 .. floor(p)+HALF */
    while (o < cap && g_rsPh < last) {
        long i0 = (long)floor(g_rsPh);
        double fr = g_rsPh - (double)i0;
        float sl = 0.f, sr = 0.f;
        for (int j = -RS_HALF + 1; j <= RS_HALF; j++) {
            float k = rs_k(fr - j);
            sl += k * g_rsExt[(i0 + j) * 2]; sr += k * g_rsExt[(i0 + j) * 2 + 1];
        }
        g_rsOut[o * 2] = sl; g_rsOut[o * 2 + 1] = sr;
        o++; g_rsPh += step;
    }
    g_rsPh -= (double)n;
    memcpy(g_rsH, g_rsExt + (size_t)n * 2, sizeof g_rsH);   /* last TAPS frames of ext */
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
    treble_update();                                   /* before eq_update, which clears the rate-change flag */
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
    double ea = 0.0, eb = 0.0;
    double es[SPEC_N] = { 0 };
    const bool specOn = atomic_load(&g_specOn);
    const bool pcmOn = atomic_load(&g_pcmOn);
    unsigned pcmStart = atomic_load(&g_pcmW);
    if (specOn) spec_update((float)(g_src ? g_src : g_rate));
    bands_update();
    while (done < count) {
        unsigned n = count - done; if (n > 4096) n = 4096;
        for (unsigned i = 0; i < n; i++) {
            float x = in[(done + i) * 2] * k16, y = in[(done + i) * 2 + 1] * k16;
            float m = (x + y) * 0.5f;
            float va = vbq_run(&g_vb[1], vbq_run(&g_vb[0], m)), vb = vbq_run(&g_vb[3], vbq_run(&g_vb[2], m));
            ea += va * va; eb += vb * vb;
            if (specOn) for (int b = 0; b < SPEC_N; b++) { float s = sbp_run(&g_sb[b], m); es[b] += s * s; }
            if (pcmOn) { unsigned pi = (pcmStart + done + i) % PCM_N; g_pcm[pi * 2] = x; g_pcm[pi * 2 + 1] = y; }
            if (g_eqActive) { x = eq_run(x, 0); y = eq_run(y, 1); }
            if (g_trebleActive) { x = treble_run(x, 0); y = treble_run(y, 1); }
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
    float ra = count ? sqrtf((float)(ea / count)) : 0.f, rb = count ? sqrtf((float)(eb / count)) : 0.f;
    g_ring[wi % RING] = (peak_t){ when, pl > 1.f ? 1.f : pl, pr > 1.f ? 1.f : pr, ra, rb };
    for (int b = 0; b < SPEC_N; b++) g_spec[(wi % RING) * SPEC_N + b] = (specOn && count) ? sqrtf((float)(es[b] / count)) : 0.f;
    if (pcmOn && count) {
        unsigned bw = atomic_load(&g_pcmBlkW);
        g_pcmBlk[bw % PCM_BLK] = (pcmblk_t){ when, pcmStart, count, (int)(g_src ? g_src : g_rate) };
        atomic_store(&g_pcmW, pcmStart + count);
        atomic_store(&g_pcmBlkW, bw + 1);
    }
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
    atomic_store(&g_r, atomic_load(&g_w)); atomic_store(&g_r2, atomic_load(&g_w));
    limit_reset();                                     /* seek: nothing of the old position comes out */
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
    limit_reset();
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
    /* An 11th value: treble shelf in dB (0 when the array is the old 10-band one). */
    jfloat tr = 0.f;
    if (eq && (*env)->GetArrayLength(env, eq) >= NB + 1) (*env)->GetFloatArrayRegion(env, eq, NB, 1, &tr);
    atomic_store(&g_trebleTarget, tr);
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

/* [setup calls, setup result, play calls, frames written, last write, start result, frames heard, stream state, pts-now µs, rate, open result, volume×1000, mute, stream number] */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_stats(JNIEnv *env, jobject thiz, jlongArray out) {
    (void)thiz;
    jlong v[14];
    pthread_mutex_lock(&g_lock);
    v[6] = g_stream ? AAudioStream_getFramesRead(g_stream) : -1;
    v[7] = g_stream ? AAudioStream_getState(g_stream) : -1;
    pthread_mutex_unlock(&g_lock);
    v[0] = atomic_load(&st_setup); v[1] = atomic_load(&st_setupRes); v[2] = atomic_load(&st_play);
    v[3] = atomic_load(&st_frames); v[4] = atomic_load(&st_lastWrite); v[5] = atomic_load(&st_startRes);
    v[8] = atomic_load(&st_ptsDelta); v[9] = atomic_load(&st_rate); v[10] = atomic_load(&st_open);
    v[11] = (jlong)(atomic_load(&g_vol) * 1000.f); v[12] = atomic_load(&g_mute); v[13] = atomic_load(&st_gen);
    (*env)->SetLongArrayRegion(env, out, 0, (*env)->GetArrayLength(env, out) < 14 ? 13 : 14, v);
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

/* Visualisation bands: lower and upper limit of the frequency range followed. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setBands(JNIEnv *env, jobject thiz, jfloat lo, jfloat hi) {
    (void)env; (void)thiz;
    if (lo < 10.f) lo = 10.f;
    if (hi < lo * 1.2f) hi = lo * 1.2f;
    atomic_store(&g_bandLo, lo); atomic_store(&g_bandHi, hi);
}

/* Band levels (RMS, 0..1) of the audio being heard now: out[0] = lower part, out[1] = upper part. Own read position. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_readBands(JNIEnv *env, jobject thiz, jfloatArray out) {
    (void)thiz;
    float a = 0.f, b = 0.f;
    int64_t now = now_us();
    unsigned r = atomic_load(&g_r2), w = atomic_load(&g_w);
    if (w - r > RING) r = w - RING;
    while (r != w && g_ring[r % RING].pts <= now) {
        if (g_ring[r % RING].ba > a) a = g_ring[r % RING].ba;
        if (g_ring[r % RING].bb > b) b = g_ring[r % RING].bb;
        r++;
    }
    atomic_store(&g_r2, r);
    jfloat v[2] = { a, b };
    (*env)->SetFloatArrayRegion(env, out, 0, 2, v);
}

/* Spectrum (SPEC_N bands, RMS) of the audio being heard now: the loudest value of each band since the last read. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_readSpectrum(JNIEnv *env, jobject thiz, jfloatArray out) {
    (void)thiz;
    float v[SPEC_N] = { 0 };
    int64_t now = now_us();
    unsigned r = atomic_load(&g_r3), w = atomic_load(&g_w);
    if (w - r > RING) r = w - RING;
    while (r != w && g_ring[r % RING].pts <= now) {
        for (int b = 0; b < SPEC_N; b++) { float x = g_spec[(r % RING) * SPEC_N + b]; if (x > v[b]) v[b] = x; }
        r++;
    }
    atomic_store(&g_r3, r);
    (*env)->SetFloatArrayRegion(env, out, 0, SPEC_N, v);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setSpectrum(JNIEnv *env, jobject thiz, jboolean on) {
    (void)env; (void)thiz;
    atomic_store(&g_specOn, on ? true : false);
}

JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setPreferredRate(JNIEnv *env, jobject thiz, jint hz) {
    (void)env; (void)thiz;
    atomic_store(&g_prefRate, (hz >= 8000 && hz <= 192000) ? hz : 0);
}

/* Treble band shape (Settings → Audio → Treble band): type 0 shelf / 1 peak, frequency Hz, slope or Q. */
JNIEXPORT void JNICALL
Java_app_vlcinterface_player_NativeAudio_setTrebleBand(JNIEnv *env, jobject thiz, jint type, jfloat hz, jfloat width) {
    (void)env; (void)thiz;
    if (hz < 500.f) hz = 500.f;
    if (hz > 20000.f) hz = 20000.f;
    if (width < 0.1f) width = 0.1f;
    if (type == 0 && width > 1.f) width = 1.f;
    atomic_store(&g_trebleType, type == 1 ? 1 : 0);
    atomic_store(&g_trebleHz, hz);
    atomic_store(&g_trebleW, width);
    atomic_fetch_add(&g_trebleGen, 1);
}
