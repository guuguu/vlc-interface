package app.vlcinterface.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android screen saver (while charging): the clock chosen in Clock aspect, dimmed, drifting very slowly around the
 * screen (no burn-in). Turned on in Android: Settings → Display → Screen saver.
 */
class ClockDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        reg.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val v = ComposeView(this)
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setContent {
            val aspect = Alarms.state.collectAsState().value.aspect
            var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1000); t = System.currentTimeMillis() } }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                // As big as fits while still leaving room for a slight slow drift (burn-in protection).
                val side = minOf(maxWidth, maxHeight) * 0.92f
                val freeX = (maxWidth - side) / 2
                val freeY = (maxHeight - side) / 2
                // A full slow loop takes about 17 min sideways and 23 min up and down.
                val s = t / 1000.0
                // Slight movement only: a few % of the clock's size, never off the screen.
                val dx = minOf(freeX, side * 0.04f) * sin(s / 160.0).toFloat()
                val dy = minOf(freeY, side * 0.04f) * cos(s / 220.0).toFloat()
                // Dimming: only here, on the charging screen saver.
                app.vlcinterface.ui.ClockFace(aspect, Modifier.offset(freeX + dx, freeY + dy).size(side).alpha(1f - aspect.dim.coerceIn(0, 90) / 100f))
            }
        }
        setContentView(v)
        reg.currentState = Lifecycle.State.STARTED
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); reg.currentState = Lifecycle.State.RESUMED }
    override fun onDreamingStopped() { super.onDreamingStopped(); reg.currentState = Lifecycle.State.STARTED }
    override fun onDetachedFromWindow() { reg.currentState = Lifecycle.State.DESTROYED; super.onDetachedFromWindow() }

    companion object {
        /** null = Android doesn't tell; true / false otherwise. */
        data class Status(val available: Boolean, val chosen: Boolean?, val enabled: Boolean?, val whileCharging: Boolean?)

        fun status(ctx: Context): Status {
            val available = ctx.packageManager.resolveActivity(Intent(Settings.ACTION_DREAM_SETTINGS), 0) != null
            val me = ComponentName(ctx, ClockDream::class.java)
            fun sec(k: String): String? = try { Settings.Secure.getString(ctx.contentResolver, k) } catch (_: Throwable) { null }
            val comps = sec("screensaver_components")
            val chosen = comps?.let { c -> c.split(',').any { ComponentName.unflattenFromString(it) == me } }
            val enabled = sec("screensaver_enabled")?.let { it == "1" }
            val charging = sec("screensaver_activate_on_sleep")?.let { it == "1" }
            return Status(available, chosen, enabled, charging)
        }

        fun open(ctx: Context) {
            try { ctx.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
