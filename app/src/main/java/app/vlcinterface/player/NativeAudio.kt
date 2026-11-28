package app.vlcinterface.player

import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer

/**
 * App-owned audio output (native, AAudio). libVLC gives us the decoded stereo sound;
 * we apply balance + preamp ourselves and measure real left/right levels for the VU meters.
 * If it cannot attach on this phone/libVLC build, [active] stays false and libVLC plays as before.
 */
object NativeAudio {
    private val loaded: Boolean = try { System.loadLibrary("vlciaudio"); true } catch (t: Throwable) { Log.w("NativeAudio", "load failed", t); false }

    @Volatile var active: Boolean = false
        private set
    /** Why it is not active (shown in Settings → Sound). */
    @Volatile var reason: String = ""
        private set

    private external fun attach(libObj: Long, mpObj: Long): Int
    private external fun detach()
    private external fun setGains(left: Float, right: Float, limiter: Boolean)
    private external fun setGainsEq(left: Float, right: Float, limiter: Boolean, eq: FloatArray)
    private external fun readPeaks(out: FloatArray)
    private external fun readBands(out: FloatArray)
    private external fun setBands(lo: Float, hi: Float)
    private external fun readSpectrum(out: FloatArray)
    private external fun setSpectrum(on: Boolean)
    private external fun stats(out: LongArray)
    private external fun reroute()
    private external fun setDevice(id: Int)
    private external fun setAppVolume(v: Float)
    private external fun setTrebleBand(type: Int, hz: Float, width: Float)
    private external fun captureStart(seconds: Int): Boolean
    private external fun captureInfo(out: LongArray)
    private external fun captureRead(input: ShortArray, output: FloatArray)
    /** Sound check: capture [seconds] of the engine's input (from libVLC) and output (to Android). */
    fun capture(seconds: Int): Boolean = active && try { captureStart(seconds) } catch (_: Throwable) { false }
    /** [armed, input frames, output frames, limiter frames, src rate, device rate, eq on, treble on, gainL, gainR, app vol, libVLC vol (×1e6)] */
    fun captureState(): LongArray? = try { LongArray(12).also { captureInfo(it) } } catch (_: Throwable) { null }
    fun captureTake(inFrames: Int, outFrames: Int): Pair<ShortArray, FloatArray>? = try {
        val a = ShortArray(inFrames * 2); val b = FloatArray(outFrames * 2); captureRead(a, b); a to b
    } catch (_: Throwable) { null }
    /** Treble band shape: shelf (0) or peak (1), frequency, slope or Q (Settings → Audio → Treble band). */
    fun trebleBand(peak: Boolean, hz: Float, width: Float) { if (active) try { setTrebleBand(if (peak) 1 else 0, hz, width) } catch (_: Throwable) {} }
    private external fun setDeep(on: Boolean)
    private external fun setPreferredRate(hz: Int)

    /** Music: biggest output buffer (steady Bluetooth); video: normal buffer (picture and sound in step). */
    fun deepBuffer(on: Boolean) { if (active) try { setDeep(on) } catch (_: Throwable) {} }

    /** App volume (0…2, 1 = unchanged) for the device the app plays on by itself. */
    fun appVolume(v: Float) { if (active) try { setAppVolume(v) } catch (_: Throwable) {} }

    /** Plays on this device (AudioDeviceInfo id; 0 = Android's default). */
    fun device(id: Int) { if (active) try { setDevice(id) } catch (_: Throwable) {} }

    /** Output device changed: reopen the sound stream on the new device. */
    fun outputChanged() { if (active) try { reroute() } catch (_: Throwable) {} }
    private external fun adjust(libObj: Long, mpObj: Long, enable: Boolean, hue: Float, sat: Float, contrast: Float, brightness: Float, gamma: Float): Int

    /** Live picture hue (−180…180°) / saturation (0…3); false if this libVLC build can't do it live. */
    fun picture(lib: LibVLC, mp: MediaPlayer, enable: Boolean, hue: Float, sat: Float, contrast: Float = 1f, brightness: Float = 1f, gamma: Float = 1f): Boolean =
        loaded && try { adjust(handle(lib), handle(mp), enable, hue, sat, contrast, brightness, gamma) == 0 } catch (_: Throwable) { false }

    /** Turned off for this app run after the watchdog saw no sound come out. */
    @Volatile var failed: Boolean = false
        private set

    data class Stats(val setups: Long, val setupResult: Long, val plays: Long, val written: Long, val lastWrite: Long,
                     val startResult: Long, val heard: Long, val state: Long, val ptsDeltaUs: Long, val rate: Long,
                     val openResult: Long, val volume: Float, val mute: Boolean, val stream: Long = 0,
                     /** Milliseconds since the sound last had a level above −66 dBFS (−1: never yet). */
                     val quietMs: Long = -1,
                     /** Frames that went over full scale before the limiter (clipping it caught), total since start. */
                     val overFrames: Long = 0,
                     /** Highest level before the limiter since the last read (1.0 = full scale), 0 when none went over. */
                     val overPeak: Float = 0f) {
        override fun toString() = "setup $setups/$setupResult, open $openResult, start $startResult, state $state, rate $rate, " +
            "blocks $plays, written $written, heard $heard, last write $lastWrite, pts−now ${ptsDeltaUs / 1000} ms, " +
            "volume ${"%.2f".format(volume)}${if (mute) " muted" else ""}, stream #$stream"
    }

    fun stats(): Stats? {
        if (!active) return null
        val a = LongArray(18)
        return try { stats(a); Stats(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11] / 1000f, a[12] != 0L, a[13],
            if (a[14] == 0L) -1L else (a[15] - a[14]) / 1000, a[16], a[17] / 1e6f) } catch (_: Throwable) { null }
    }

    /** Gives up on our output for this run; the player then uses libVLC's own output. */
    fun markFailed(why: String) { failed = true; reason = why }
    /** Lets the app output be tried again (chosen again in Settings). */
    fun resetFailed() { failed = false; reason = "" }

    private fun handle(o: Any): Long {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("mInstance"); f.isAccessible = true
                return f.getLong(o)
            } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return 0
    }

    fun attachTo(lib: LibVLC, mp: MediaPlayer, wanted: Boolean): Boolean {
        active = false
        if (!wanted) { reason = "chosen in Settings"; return false }
        if (failed) return false
        if (!loaded) { reason = "native library missing"; return false }
        val r = try { attach(handle(lib), handle(mp)) } catch (t: Throwable) { -9 }
        active = r == 0
        reason = when (r) { 0 -> ""; -1 -> "libVLC audio callbacks not found"; -2 -> "libVLC player not recognised"; else -> "error $r" }
        return active
    }

    fun release() { if (active) try { detach() } catch (_: Throwable) {}; active = false }

    fun gains(left: Float, right: Float, limiter: Boolean) { if (active) setGains(left, right, limiter) }
    /** Level and tone (10 band gains, dB) in one go: they change together, gliding, in the app's own output. */
    fun gainsEq(left: Float, right: Float, limiter: Boolean, eq: FloatArray) { if (active) setGainsEq(left, right, limiter, eq) }

    /** Left/right peak (0..1) of what is being heard right now. */
    /** Visualisation: RMS of the lower / upper part of the frequency range followed (0 when not active). */
    fun bands(out: FloatArray) { if (active) try { readBands(out) } catch (_: Throwable) { out[0] = 0f; out[1] = 0f } else { out[0] = 0f; out[1] = 0f } }
    /** Output stream at this rate (Bluetooth link rate), 0 = the song's rate. Reopens the stream when it changes. */
    @Volatile private var prefRate = 0
    fun preferRate(hz: Int) {
        if (!loaded || hz == prefRate) return
        prefRate = hz
        try { setPreferredRate(hz); if (active) reroute() } catch (_: Throwable) {}
    }
    fun bandRange(lo: Float, hi: Float) { if (loaded) try { setBands(lo, hi) } catch (_: Throwable) {} }
    /** Spectrum analyser: 32 bands (≈ 40 Hz … 16 kHz), measured only while [spectrumOn] is set. */
    const val SPEC_N = 32
    /** Counted: on while at least one live spectrum picture is shown. */
    private var specUsers = 0
    @Synchronized fun spectrumOn(on: Boolean) {
        specUsers = (specUsers + if (on) 1 else -1).coerceAtLeast(0)
        if (loaded) try { setSpectrum(specUsers > 0) } catch (_: Throwable) {}
    }
    fun spectrum(out: FloatArray) { if (active) try { readSpectrum(out) } catch (_: Throwable) { out.fill(0f) } else out.fill(0f) }
    fun peaks(out: FloatArray) { if (active) readPeaks(out) else { out[0] = 0f; out[1] = 0f } }
}
