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
    private external fun readPeaks(out: FloatArray)
    private external fun stats(out: LongArray)
    private external fun reroute()
    private external fun setDevice(id: Int)
    private external fun setAppVolume(v: Float)
    private external fun setDeep(on: Boolean)

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
                     val openResult: Long, val volume: Float, val mute: Boolean) {
        override fun toString() = "setup $setups/$setupResult, open $openResult, start $startResult, state $state, rate $rate, " +
            "blocks $plays, written $written, heard $heard, last write $lastWrite, pts−now ${ptsDeltaUs / 1000} ms, " +
            "volume ${"%.2f".format(volume)}${if (mute) " muted" else ""}"
    }

    fun stats(): Stats? {
        if (!active) return null
        val a = LongArray(13)
        return try { stats(a); Stats(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11] / 1000f, a[12] != 0L) } catch (_: Throwable) { null }
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

    /** Left/right peak (0..1) of what is being heard right now. */
    fun peaks(out: FloatArray) { if (active) readPeaks(out) else { out[0] = 0f; out[1] = 0f } }
}
