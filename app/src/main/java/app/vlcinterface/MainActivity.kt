package app.vlcinterface

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import app.vlcinterface.player.PlayItem
import app.vlcinterface.ui.AppRoot
import app.vlcinterface.ui.Navigator
import app.vlcinterface.ui.Screen

class MainActivity : ComponentActivity() {
    val nav = Navigator()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        App.instance.output.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this
        permissions.launch(arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT,
        ))
        handleIntent(intent)
        setContent { AppRoot(nav) { finish() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_ORIGIN, false) == true) {
            i.removeExtra(EXTRA_OPEN_ORIGIN)
            app.vlcinterface.ui.openOrigin(nav)
            return
        }
        if (i?.action == Intent.ACTION_VIEW) {
            val data = i.data ?: return
            App.instance.player.play(PlayItem(data.toString(), data.lastPathSegment ?: "Video"))
            nav.go(Screen.Player)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val st = App.instance.player.state.value
        if (nav.top is Screen.Player && st.isPlaying && st.videoW > 0) enterPip()
    }

    fun enterPip() {
        val st = App.instance.player.state.value
        val w = st.videoW.coerceAtLeast(1); val h = st.videoH.coerceAtLeast(1)
        val ratio = Rational(w, h).let { r ->
            when {
                r.toFloat() > 2.39f -> Rational(239, 100)
                r.toFloat() < 0.42f -> Rational(42, 100)
                else -> r
            }
        }
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        val p = App.instance.player
        p.saveProgress()
        val st = p.state.value
        // Video without background audio pauses when the app leaves the screen (except in PiP).
        if (!isInPictureInPictureMode && st.item != null && st.item.isRadio.not() && !App.instance.store.current.settings.backgroundAudio) p.pause()
    }

    companion object {
        const val EXTRA_OPEN_ORIGIN = "open_origin"
        var instance: MainActivity? = null
        val inPip = mutableStateOf(false)
    }
}
