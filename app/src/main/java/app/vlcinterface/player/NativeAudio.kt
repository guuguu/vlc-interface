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

    fun attachTo(lib: LibVLC, mp: MediaPlayer): Boolean {
        active = false
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
