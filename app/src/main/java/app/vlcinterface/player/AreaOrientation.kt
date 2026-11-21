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

    // libVLC creates its video surfaces with the layout inflater: a plain wrapper hands out the activity's inflater, so
    // the surfaces were made with the activity's context (portrait) and this orientation was never seen (23:42). The
    // inflater is cloned for this context, so every view libVLC inflates inside the video view reads it.
    private var inflater: android.view.LayoutInflater? = null
    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            return inflater ?: android.view.LayoutInflater.from(baseContext).cloneInContext(this).also { inflater = it }
        }
        return super.getSystemService(name)
    }
}
