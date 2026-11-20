package app.vlcinterface.player

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * projectM (MilkDrop presets) for the visualisation: native bridge (projectmjni.c) and the drawing surface.
 * The four bundled presets live in assets/projectm (pm1…pm4, credits in CREDITS.txt).
 */
object ProjectM {
    val loaded: Boolean = try { System.loadLibrary("projectmjni"); true } catch (t: Throwable) { Log.w("projectM", "load failed", t); false }

    @JvmStatic private external fun nCreate(w: Int, h: Int, preset: String): Long
    @JvmStatic private external fun nResize(h: Long, w: Int, hh: Int)
    @JvmStatic private external fun nLoad(h: Long, preset: String)
    @JvmStatic private external fun nRender(h: Long)
    @JvmStatic private external fun nDestroy(h: Long)
    @JvmStatic private external fun nEnable(on: Boolean)

    /** The bundled presets: key → (name shown, asset file). */
    val PRESETS = linkedMapOf(
        "pm1" to ("MilkDrop: Tunnel" to "projectm/pm1.milk"),
        "pm2" to ("MilkDrop: Kaleidoscope" to "projectm/pm2.milk"),
        "pm3" to ("MilkDrop: Rings" to "projectm/pm3.milk"),
        "pm4" to ("MilkDrop: Starburst" to "projectm/pm4.milk"),
    )

    fun presetText(ctx: Context, key: String): String = try {
        ctx.assets.open(PRESETS[key]?.second ?: "projectm/pm1.milk").use { it.readBytes().decodeToString() }
    } catch (_: Exception) { "" }

    /** The sound engine's sample tap: on while at least one projectM picture is drawing. */
    private var users = 0
    @Synchronized fun feed(on: Boolean) {
        users = (users + if (on) 1 else -1).coerceAtLeast(0)
        if (loaded) try { nEnable(users > 0) } catch (_: Throwable) {}
    }

    /**
     * A drawing surface for one preset. [drawing] false (not visible, paused): no frames are drawn and the sample tap
     * is let go; the projectM instance (and its picture) is kept, so it carries on when shown again. [release] frees it.
     */
    class View(ctx: Context, key: String) : GLSurfaceView(ctx) {
        @Volatile private var handle = 0L
        @Volatile private var preset = presetText(ctx, key)
        @Volatile private var newPreset: String? = null
        @Volatile private var w = 1; @Volatile private var h = 1
        private var feeding = false
        private var currentKey = key

        init {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(object : Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    // A new GL context (first time, or the old one was lost): a fresh instance for it.
                    if (handle != 0L) { try { nDestroy(handle) } catch (_: Throwable) {}; handle = 0L }
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    w = width.coerceAtLeast(1); h = height.coerceAtLeast(1)
                    if (!loaded) return
                    if (handle == 0L) handle = try { nCreate(w, h, preset) } catch (_: Throwable) { 0L }
                    else nResize(handle, w, h)
                }
                override fun onDrawFrame(gl: GL10?) {
                    if (!loaded || handle == 0L) return
                    newPreset?.let { nLoad(handle, it); newPreset = null }
                    nRender(handle)
                }
            })
            renderMode = RENDERMODE_CONTINUOUSLY
        }

        fun setPreset(key: String) {
            if (key == currentKey) return
            currentKey = key
            val t = presetText(context, key); preset = t; newPreset = t
        }

        /** Shown and in the foreground: draws every frame; otherwise nothing is drawn (picture kept). */
        fun drawing(on: Boolean) {
            if (on != feeding) { feeding = on; feed(on) }
            renderMode = if (on) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        }

        /** Gone for good (cover / clock in its place, page closed): the instance is freed on the GL thread. */
        fun release() {
            drawing(false)
            val done = java.util.concurrent.CountDownLatch(1)
            queueEvent { if (handle != 0L) { try { nDestroy(handle) } catch (_: Throwable) {}; handle = 0L }; done.countDown() }
            try { done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            onPause()
        }

        /** App in the foreground again: the GL thread carries on with the same picture (context kept while paused). */
        fun appResumed() = onResume()
        /** App paused / screen off: the GL thread stops (context and picture kept). */
        fun appPaused() { drawing(false); onPause() }
    }
}
