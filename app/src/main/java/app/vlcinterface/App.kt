package app.vlcinterface

import android.app.Application
import app.vlcinterface.data.Store
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        player = PlayerController(this, store)
        output = OutputRouter(this)
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

    companion object {
        lateinit var instance: App
            private set
    }
}
