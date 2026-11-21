package app.vlcinterface.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources

/**
 * The video view's own idea of the screen orientation (2026-11-21 21:39).
 *
 * libVLC's sizing helper (VideoHelper) assumes the video view fills the screen: when the phone is upright and the view
 * is wider than tall, it swaps the view's width and height before fitting the picture — so a wide film in the upper
 * part of the upright screen was sized for a tall, narrow view and drawn small in the middle (1080 × 600 area →
 * picture for 600 × 1080). Sideways nothing is swapped, which is why turning the phone fixed it.
 *
 * The helper reads the orientation from the view's resources. This context gives the video view resources whose
 * orientation follows the shape of the view itself — landscape when it is wider than tall, portrait when taller —
 * which is the case the helper handles right, in every format. Everything else is the activity's own.
 */
class AreaOrientationContext(base: Context) : ContextWrapper(base) {
    /** Wider than tall (set from the view's size on every layout). */
    @Volatile var wide = false

    @Suppress("DEPRECATION")
    private val res: Resources = object : Resources(base.resources.assets, base.resources.displayMetrics, base.resources.configuration) {
        override fun getConfiguration(): Configuration = Configuration(super.getConfiguration()).also {
            it.orientation = if (wide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
    }

    override fun getResources(): Resources = res

    // (1.72 also handed libVLC its own layout inflater here; removed in 1.73 while the start-up crash of 1.72 was
    // found — the picture size is made right by the size check's backstop in PlayerController.)
}
