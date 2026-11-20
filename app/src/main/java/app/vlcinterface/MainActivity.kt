package app.vlcinterface

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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
        // Volume buttons always control media volume in this app (not the ringer while playback is paused / resuming).
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        // Android 13+: media + notification permissions; Android 12: the storage permission (notifications need none).
        permissions.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_CONNECT))
        handleIntent(intent)
        // First start (or never finished): the setup wizard opens over the Library.
        if (savedInstanceState == null && !app.vlcinterface.App.instance.store.current.settings.wizardDone) nav.go(app.vlcinterface.ui.Screen.Wizard())
        // First install: only the usual media folders join the Library by themselves; others wait for approval (wizard → Library).
        if (savedInstanceState == null) try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val st = app.vlcinterface.App.instance.store
            if (pi.firstInstallTime == pi.lastUpdateTime && !st.current.settings.phoneFoldersSet && !st.current.settings.wizardDone)
                st.update { it.copy(settings = it.settings.copy(phoneFoldersSet = true)) }
        } catch (_: Exception) {}
        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                app.vlcinterface.ui.ExplainHost { AppRoot(nav) { finish() } }
                // An alarm ringing (or snoozed / being checked): always a way back to its screen, whatever page is open.
                app.vlcinterface.alarm.AlarmBanner(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                app.vlcinterface.ui.ShortNoteHost(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app while an alarm rings: its screen comes straight up (to stop or snooze it).
        val ph = app.vlcinterface.alarm.AlarmEngine.ui.value.phase
        if (ph == "ringing" || ph == "confirming") try {
            startActivity(android.content.Intent(this, app.vlcinterface.alarm.AlarmActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /**
     * Volume buttons. Normally Android handles them. While the app plays on a device it picked itself and Android
     * routes media elsewhere (speaker picked while Bluetooth is connected), Android's buttons would change the other
     * device and nothing seemed to happen: then they change the app's volume on the device that is playing.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        // Bluetooth quality mode: always the device's (Android's) volume, the app's own volume stays at 100 %.
        val btq = app.vlcinterface.player.BtQuality.on()
        if ((k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && (!App.instance.output.overriding || btq)) {
            // Media volume, every time: right after resuming, Android could think nothing plays and change another volume.
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val am = getSystemService(android.media.AudioManager::class.java)
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI)
            }
            return true
        }
        if ((k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && App.instance.output.overriding) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val pct = App.instance.player.stepAppVolume(k == android.view.KeyEvent.KEYCODE_VOLUME_UP)
                volToast?.cancel()
                volToast = android.widget.Toast.makeText(this, "Volume on ${App.instance.player.currentOutputName.ifEmpty { "this device" }}: $pct %", android.widget.Toast.LENGTH_SHORT).also { it.show() }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private var volToast: android.widget.Toast? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(app.vlcinterface.sources.RgScanService.EXTRA_OPEN_RGSCAN, false) == true) {
            i.removeExtra(app.vlcinterface.sources.RgScanService.EXTRA_OPEN_RGSCAN)
            if (nav.top !is Screen.RgScan) nav.go(Screen.RgScan)
            return
        }
        if (i?.getBooleanExtra(EXTRA_OPEN_ORIGIN, false) == true) {
            i.removeExtra(EXTRA_OPEN_ORIGIN)
            app.vlcinterface.ui.openOrigin(nav)
            return
        }
        if (i?.action == Intent.ACTION_VIEW) {
            val data = i.data ?: return
            val name = app.vlcinterface.sources.fileNameOf(this, data.toString())
            val item = PlayItem(data.toString(), name.ifEmpty { data.lastPathSegment ?: "Video" }, fileName = name)
            App.instance.player.play(item)
            nav.go(if (item.isMusic) Screen.Music else Screen.Player)
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
        // Leaving the screen: write any change still waiting, in the background.
        kotlin.concurrent.thread { App.instance.store.flush() }
    }

    companion object {
        const val EXTRA_OPEN_ORIGIN = "open_origin"
        var instance: MainActivity? = null
        val inPip = mutableStateOf(false)
    }
}
