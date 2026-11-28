package app.vlcinterface

import app.vlcinterface.data.CrashLog

import kotlinx.coroutines.launch
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
    /** The output router, or null while the app is still starting (the player is made before it). */
    fun outputOrNull(): OutputRouter? = if (::output.isInitialized) output else null
    lateinit var media: MediaCenter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        ServerLog.init(this)
        app.vlcinterface.data.CrashLog.init(this)        // crashes now, and why the app ended last time
        // Started again soon after Exit (2026-11-28 03:09): what started it is written to the Player log (Android 15+
        // says why: an alarm, a broadcast, a service, a screen saver …; older phones: just that it started).
        try {
            val exitAt = getSharedPreferences("volkeep", MODE_PRIVATE).getLong("exitAt", 0L)
            if (exitAt > 0 && System.currentTimeMillis() - exitAt < 30 * 60_000L) {
                var why = "reason not given by Android before version 15"
                var byHand = false
                if (android.os.Build.VERSION.SDK_INT >= 35) try {
                    val am = getSystemService(android.app.ActivityManager::class.java)
                    am.getHistoricalProcessStartReasons(1).firstOrNull()?.let { r ->
                        byHand = r.reason == 6 || r.reason == 7 || r.reason == 11
                        val names = mapOf(0 to "an alarm", 1 to "a backup", 2 to "the phone starting", 3 to "a broadcast", 4 to "a content provider",
                            5 to "a scheduled job", 6 to "the launcher (opened by hand)", 7 to "recent apps", 8 to "other", 9 to "a push message",
                            10 to "a service", 11 to "an activity being opened")
                        why = "started by " + (names[r.reason] ?: "reason ${r.reason}") +
                            (r.intent?.let { i -> " · " + listOfNotNull(i.action, i.component?.shortClassName).joinToString(" ") } ?: "")
                    }
                } catch (_: Throwable) {}
                // An error entry, so it is in "Collect error logs" (2026-11-28 10:10: the process still seen after Exit).
                // Only an unexpected start is written (13:28: no entry for each start — opened by hand isn't logged).
                if (android.os.Build.VERSION.SDK_INT >= 35 && !byHand) ServerLog.log("player", "start", "", "", false, "App started again ${(System.currentTimeMillis() - exitAt) / 1000} s after Exit — $why")
            }
            getSharedPreferences("volkeep", MODE_PRIVATE).edit().remove("exitAt").apply()
        } catch (_: Exception) {}
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
        // Home-network indexing starts when the app's screen is opened (uiOpened), not when Android starts the process in
        // the background for an alarm, a broadcast or a job (13:20: 149 % CPU in a process nobody had opened).
        // The volume is kept only: after Exit and opened again (within "Reset volume after"), or when the app was restarted by a
        // short hiccup of Bluetooth / the sound system (back within 2 min). Otherwise: the start volume.
        val vk = getSharedPreferences("volkeep", MODE_PRIVATE)
        val asleep = System.currentTimeMillis() - vk.getLong("alive", 0)
        // After Exit: kept within Settings → Audio → "Reset volume after" (0 = never kept); a restart from the app: never kept.
        val keepMs = store.current.settings.idleResetMin.let { if (it <= 0) 0L else it * 60_000L }
        val keep = vk.getLong("alive", 0) > 0L && (if (vk.getBoolean("cleanExit", false)) asleep <= keepMs else asleep <= BLIP_MS)
        if (!keep) applyStartVolume()
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
        app.vlcinterface.alarm.Alarms.init(this)               // alarm clock: set again, missed ones handled
        app.vlcinterface.alarm.Weather.init(this)
        app.vlcinterface.player.VideoAuto.init(this)
        // Phone videos deleted since last time: forget what was kept for them (a few seconds after start, off the main thread).
        Thread { try { Thread.sleep(5000); app.vlcinterface.player.VideoFileCleanup.checkPhone(this) } catch (_: Exception) {} }.start()
        app.vlcinterface.alarm.AlarmScheduler.reschedule(this, checkMissed = true)
        try { app.vlcinterface.alarm.ShortAlarms.scheduleAll(this) } catch (_: Exception) {}
        if (app.vlcinterface.alarm.Alarms.active.isNotEmpty()) app.vlcinterface.alarm.AlarmEngine.guardCheck(this)
        app.vlcinterface.sources.VpnLink.init(this)          // home NAS over a VPN (ZeroTier …)   // radio: keeps the playing station ready to save songs
        player.currentOutputName = output.active?.name ?: ""
        // Started in the background with nothing to do (13:20): no screen opened, nothing played, no alarm ringing / snoozed,
        // no screen saver, no ReplayGain scan, no radio recording — after 90 s the process ends itself (logged). Alarms still
        // ring: Android starts the app again for them.
        android.os.Handler(android.os.Looper.getMainLooper()).let { h ->
            h.postDelayed(object : Runnable { override fun run() {
                if (uiStarted || everPlayed) return
                val busy = try {
                    app.vlcinterface.alarm.AlarmEngine.ui.value.phase != "idle" || app.vlcinterface.alarm.ClockDream.on ||
                        app.vlcinterface.sources.RgScanner.state.value.running || app.vlcinterface.sources.RadioCapture.status.value.running
                } catch (_: Exception) { true }
                if (busy) { h.postDelayed(this, 60_000); return }
                android.os.Process.killProcess(android.os.Process.myPid())
            } }, 90_000)
        }
        // Every second: the volume of the output in use, kept per output (put back exactly after a short change of output).
        android.os.Handler(android.os.Looper.getMainLooper()).let { h ->
            h.post(object : Runnable { override fun run() {
                try { output.active?.name?.let { n -> volByOutput.put(n, output.volumePercent()) } } catch (_: Exception) {}
                h.postDelayed(this, 1_000)
            } })
        }
        // Every minute: alive, and the volume now (kept over a restart / a Bluetooth drop shorter than 30 min).
        android.os.Handler(android.os.Looper.getMainLooper()).let { h ->
            h.post(object : Runnable { override fun run() {
                // Not after Exit / Restart: their own note must stay (a late write made a restart keep the old volume).
                if (!exiting) try { vk.edit().putLong("alive", System.currentTimeMillis()).putInt("pct", output.volumePercent()).apply() } catch (_: Exception) {}
                h.postDelayed(this, 60_000)
            } })
        }
        output.onActiveChanged = { before, after, picked ->
            player.currentOutputName = after?.name ?: ""
            // Video playing: the audio sync kept for the new device (and this video) is put on at once.
            try { player.applyAudioSync() } catch (_: Exception) {}
            val now = System.currentTimeMillis()
            // An output that went away: its volumes kept, to put back if it returns within 30 min (Bluetooth stack
            // restarting, a short drop) instead of falling back to the defaults.
            if (before != null && (after == null || after.name != before.name)) {
                // The volume it had (2026-11-22 19:46): taken from the last reading while it was the output — read now, after the
                // change, it was the NEW output's volume (a notification moving the sound for a moment, then back, left the
                // device at the wrong level).
                lostName = before.name; lostAt = now; lostPct = volByOutput.getOrDefault(before.name, -1); lostApp = player.appVolume
            }
            // A short drop only — and not when the start volume was set meanwhile (2026-11-23 00:55: the old level, noted
            // before the reset, was put back over it).
            val back = after != null && after.name == lostName && now - lostAt < BLIP_MS && startVolumeAt < lostAt
            if (back) {
                val want = lostPct
                if (want >= 0) {
                    try {
                        if (output.volumePercent() != want) {
                            output.setVolumePercent(want)
                            ServerLog.log("player", "volume", "", "", true, (after?.name ?: "") + " back after a short change: volume put back to " + want + " %")
                        }
                    } catch (_: Exception) {}
                }
                player.setAppVolume(lostApp)
                lostName = ""
            } else if (!output.overriding) player.setAppVolume(1f)   // back on Android's routing: Android's volume again
            // The start volume was just set (a reset after the quiet time, or the app's start) and the sound moved to another
            // output within 15 s — e.g. Bluetooth connecting when playback started: that output gets the start volume too,
            // so it doesn't play at its old level (2026-11-23 00:55).
            if (!back && after != null && before?.name != after.name && startVolumeAt > 0 && now - startVolumeAt < 15_000 && !output.overriding) {
                applyStartVolume()
                ServerLog.log("player", "volume", "", "", true, after.name + " came in right after the start volume was set: set to it too")
            } else if (!back && after != null && before != null && before.name != after.name && store.current.settings.startVolOnOutput) {
                // 2026-11-28 01:12 (Settings → Audio → "Start volume on a new output"): the sound moved to another output
                // device — it starts at the Start volume there (a short drop of the same device coming back doesn't count).
                applyStartVolume()
                if (!output.overriding) player.setAppVolume(1f)
                else player.setAppVolume(store.current.settings.startVolume / output.volumePercent().coerceAtLeast(1).toFloat())
                ServerLog.log("player", "volume", "", "", true, "Output changed to " + after.name + ": set to the start volume (" + store.current.settings.startVolume + " %)")
                // Android may put the device's own remembered level back a moment after it connects (Bluetooth absolute
                // volume): checked again shortly after, and set once more if it was overwritten.
                val dev = after.name
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (output.active?.name == dev && output.volumePercent() != store.current.settings.startVolume) {
                            applyStartVolume()
                            ServerLog.log("player", "volume", "", "", true, dev + ": level changed by Android after connecting — start volume set again")
                        }
                    } catch (_: Exception) {}
                }, 1200)
            }
            // Picked in the app: the sound was already moved to it (outputChanged would send it back to Android's default).
            if (before?.name != after?.name && !picked) player.outputChanged()
            if (after != null) store.update { it.copy(lastOutput = after.name) }
            // Headphones unplugged / Bluetooth lost: fall back to the speaker paused, never blasting audio.
            val lostPersonal = before != null && before.kind != OutputKind.SPEAKER &&
                (after == null || after.kind == OutputKind.SPEAKER)
            // Not when you picked the speaker yourself in Sound output: then it just keeps playing there.
            if (lostPersonal && !picked && store.current.settings.pauseOnDisconnect) player.pause()
            // Bluetooth quality mode: link rate and 100 % app volume follow the output.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + CrashLog.guard).launch {
                kotlinx.coroutines.delay(800)
                app.vlcinterface.player.BtCodec.linkRateHz = 0
                app.vlcinterface.player.BtQuality.apply(this@App)
            }
        }
    }

    /** Fresh app start: set the phone's media volume to the chosen start volume before any sound plays. */
    /** When the start volume was last set (an output coming back right after doesn't get its older level back). */
    @Volatile var startVolumeAt = 0L
    fun applyStartVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = store.current.settings.startVolume.coerceIn(0, 100)
            val v = Math.round(pct * max / 100f)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            startVolumeAt = System.currentTimeMillis()
            try { output.active?.name?.let { n -> volByOutput.put(n, pct) } } catch (_: Exception) {}
        } catch (_: Exception) {
        }
    }

    /** Set by Exit / Restart the app: the minute "alive" note stops. */
    @Volatile var exiting = false
    /** The media volume of each output, read every second while it is the one in use (see onActiveChanged). */
    private val volByOutput = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var lostName = ""
    @Volatile private var lostAt = 0L
    @Volatile private var lostPct = -1
    @Volatile private var lostApp = 1f

    companion object {
        /** After Exit: the volume is kept if the app is opened again within this. */
        const val KEEP_MS = 30 * 60_000L
        /** Bluetooth / sound system hiccup (output gone and back, app restarted by it): kept if shorter than this. */
        const val BLIP_MS = 2 * 60_000L
        lateinit var instance: App
            private set
        /**
         * The app's screen was opened in this process / something was played (2026-11-28 13:20: the process seen at 149 %
         * CPU with the screen saver off — started in the background by an alarm or broadcast, it began indexing).
         */
        @Volatile var uiStarted = false
        @Volatile var everPlayed = false
        /** The screen opened: the background work held back for it starts now (once). */
        fun uiOpened() {
            if (uiStarted) return
            uiStarted = true
            try { app.vlcinterface.sources.NetIndex.autoStart() } catch (_: Exception) {}
        }
        const val VERSION = "0.3.0"
    }
}
