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
        app.vlcinterface.data.CrashLog.init(this)        // crashes now, and why the app ended last time
        app.vlcinterface.data.CrashLog.state = {
            val s = player.state.value
            "Playing: ${s.item?.title ?: "nothing"} (${s.item?.uri ?: ""})\nOutput: " +
                (if (app.vlcinterface.player.NativeAudio.active) "app output" else "libVLC (" + app.vlcinterface.player.NativeAudio.reason + ")") +
                "\nApp version: $VERSION"
        }
        TagCache.init(this)
        app.vlcinterface.sources.RgStore.init(this)
        app.vlcinterface.player.BatteryGuard.init(this)   // 15 % warning, stop at 10 %
        app.vlcinterface.player.ToneStore.init(this)      // loudness / treble per song
        app.vlcinterface.player.NetGuard.init(this)       // radio: Wi-Fi lost → ask; mobile data counter
        app.vlcinterface.sources.RgScanner.flushPendingSoon(this)   // cached ReplayGain writes left from last time
        app.vlcinterface.sources.CoverArt.init(this)
        app.vlcinterface.sources.NetIndex.init(this)
        app.vlcinterface.sources.NetIndex.buildMissing()      // pinned LAN folders not indexed yet: in the background
        // Start volume only after more than 30 min asleep (or the very first start). Any restart within 30 min —
        // by hand, or by something else such as a Bluetooth app restarting the stack — keeps the volume.
        val vk = getSharedPreferences("volkeep", MODE_PRIVATE)
        val asleep = System.currentTimeMillis() - vk.getLong("alive", 0)
        if (vk.getLong("alive", 0) == 0L || asleep > KEEP_MS) applyStartVolume()
        else vk.getInt("pct", -1).takeIf { it >= 0 }?.let { p -> try {
            val am = getSystemService(AudioManager::class.java); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * max / 100f), 0) } catch (_: Exception) {} }
        vk.edit().putBoolean("cleanExit", false).apply()
        player = PlayerController(this, store)
        output = OutputRouter(this)
        media = MediaCenter(this, player)
        app.vlcinterface.sources.RadioCapture.init(this)
        app.vlcinterface.sources.Prefetch.init(this)            // next network song cached ahead
        app.vlcinterface.sources.RgScanner.recoverPatches(this) // an interrupted in-place tag update put right
        app.vlcinterface.player.QueueKeeper.init(this)          // queue and song kept over a restart
        app.vlcinterface.player.QueueKeeper.restore(player)
        app.vlcinterface.sources.VpnLink.init(this)          // home NAS over a VPN (ZeroTier …)   // radio: keeps the playing station ready to save songs
        player.currentOutputName = output.active?.name ?: ""
        // Every minute: alive, and the volume now (kept over a restart / a Bluetooth drop shorter than 30 min).
        android.os.Handler(android.os.Looper.getMainLooper()).let { h ->
            h.post(object : Runnable { override fun run() {
                try { vk.edit().putLong("alive", System.currentTimeMillis()).putInt("pct", output.volumePercent()).apply() } catch (_: Exception) {}
                h.postDelayed(this, 60_000)
            } })
        }
        output.onActiveChanged = { before, after, picked ->
            player.currentOutputName = after?.name ?: ""
            val now = System.currentTimeMillis()
            // An output that went away: its volumes kept, to put back if it returns within 30 min (Bluetooth stack
            // restarting, a short drop) instead of falling back to the defaults.
            if (before != null && (after == null || after.name != before.name)) {
                lostName = before.name; lostAt = now; lostPct = try { output.volumePercent() } catch (_: Exception) { -1 }; lostApp = player.appVolume
            }
            val back = after != null && after.name == lostName && now - lostAt < KEEP_MS
            if (back) {
                if (lostPct >= 0) try { output.setVolumePercent(lostPct) } catch (_: Exception) {}
                player.setAppVolume(lostApp)
                lostName = ""
            } else if (!output.overriding) player.setAppVolume(1f)   // back on Android's routing: Android's volume again
            // Picked in the app: the sound was already moved to it (outputChanged would send it back to Android's default).
            if (before?.name != after?.name && !picked) player.outputChanged()
            if (after != null) store.update { it.copy(lastOutput = after.name) }
            // Headphones unplugged / Bluetooth lost: fall back to the speaker paused, never blasting audio.
            val lostPersonal = before != null && before.kind != OutputKind.SPEAKER &&
                (after == null || after.kind == OutputKind.SPEAKER)
            // Not when you picked the speaker yourself in Sound output: then it just keeps playing there.
            if (lostPersonal && !picked && store.current.settings.pauseOnDisconnect) player.pause()
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

    @Volatile private var lostName = ""
    @Volatile private var lostAt = 0L
    @Volatile private var lostPct = -1
    @Volatile private var lostApp = 1f

    companion object {
        /** Volumes kept over a Bluetooth drop / process restart shorter than this. */
        const val KEEP_MS = 30 * 60_000L
        lateinit var instance: App
            private set
        const val VERSION = "0.3.0"
    }
}
