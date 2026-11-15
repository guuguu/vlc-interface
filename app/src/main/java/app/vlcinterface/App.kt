package app.vlcinterface

import android.app.Application
import android.media.AudioManager
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Store
import app.vlcinterface.data.TagCache
import app.vlcinterface.player.MediaCenter
import app.vlcinterface.player.OutputKind
import app.vlcinterface.player.OutputRouter
import app.vlcinterface.player.PlayerController

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var player: PlayerController
        private set
    lateinit var output: OutputRouter
        private set
    lateinit var media: MediaCenter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        ServerLog.init(this)
        TagCache.init(this)
        app.vlcinterface.sources.RgStore.init(this)
        applyStartVolume()
        player = PlayerController(this, store)
        output = OutputRouter(this)
        media = MediaCenter(this, player)
        player.currentOutputName = output.active?.name ?: ""
        output.onActiveChanged = { before, after ->
            player.currentOutputName = after?.name ?: ""
            if (after != null) store.update { it.copy(lastOutput = after.name) }
            // Headphones unplugged / Bluetooth lost: fall back to the speaker paused, never blasting audio.
            val lostPersonal = before != null && before.kind != OutputKind.SPEAKER &&
                (after == null || after.kind == OutputKind.SPEAKER)
            if (lostPersonal && store.current.settings.pauseOnDisconnect) player.pause()
        }
    }

    /** Fresh app start: set the phone's media volume to the chosen start volume before any sound plays. */
    private fun applyStartVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = Math.round(store.current.settings.startVolume.coerceIn(0, 100) * max / 100f)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: App
            private set
        const val VERSION = "0.2.0"
    }
}
