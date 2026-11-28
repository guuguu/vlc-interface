package app.vlcinterface.player

import app.vlcinterface.data.CrashLog

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.vlcinterface.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps playback alive in the background; the notification itself is built and updated by [MediaCenter]. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + CrashLog.guard)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = App.instance
        val player = app.player
        val step = app.store.current.settings.doubleTapSeekSec * 1000L
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePause()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.prev()
            ACTION_BACK -> player.seekBy(-step)
            ACTION_FWD -> player.seekBy(step)
            ACTION_STOP -> { player.stop(); promised(); finish(); return START_NOT_STICKY }
        }
        val st = player.state.value
        // 14:40 crash (ForegroundServiceDidNotStartInTimeException, a playlist page, nothing playing): started with
        // startForegroundService, the service must go to the foreground even when it stops at once — the player had
        // already been emptied (a list loading, a stop) and it ended without that, so Android closed the app.
        if (st.item == null) { promised(); finish(); return START_NOT_STICKY }
        foreground(st)
        if (job == null) {
            // Only react to "has item" / "is playing" changes; MediaCenter updates the notification content.
            job = scope.launch {
                // Stays in the foreground (and keeps the phone awake) through the gap between two songs: losing it there
                // let Android freeze the app right after a song ended, so the next one never started and the
                // notification vanished. Only a real pause (or the end) lets it go.
                // Wi-Fi is held only for music from the network: playing phone files leaves the 2.4 GHz radio
                // quieter, which Bluetooth (same band) is grateful for.
                player.state.map { Triple(it.item != null, it.isPlaying || !it.userPaused, it.item?.uri?.let { u -> !u.startsWith("content://") && !u.startsWith("file://") && !u.startsWith("/") } == true) }
                    .distinctUntilChanged().collect { (hasItem, active, net) ->
                    when {
                        !hasItem -> finish()
                        active -> { foreground(player.state.value); hold(true, net) }
                        // Paused: classic notification (Android 12): stays in the foreground, so the notification stays at the top
                        // of the bar with its icon while a player is on (11:57); the phone may sleep (no wake / Wi-Fi lock).
                        // Modern: let go of the foreground, Android's media card stays as long as the session is active.
                        else -> { if (App.instance.media.modern) stopForeground(STOP_FOREGROUND_DETACH) else foreground(player.state.value); hold(false) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * In the foreground with the player notification. Android 12+ can refuse that while the app is in the background
     * (e.g. playback resumed by Bluetooth after a pause): the notification is then still shown as a plain one, so a
     * working player always has its notification and status-bar icon.
     */
    private fun foreground(st: PlayerState) {
        val app = App.instance
        try { startForeground(MediaCenter.ID, app.media.build(st), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        catch (_: Exception) {
            try { getSystemService(android.app.NotificationManager::class.java).notify(MediaCenter.ID, app.media.build(st)) } catch (_: Exception) {}
        }
    }

    // CPU (and Wi-Fi for network music) kept awake while a queue is playing, so the next song starts with the screen off.
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")   // WIFI_MODE_FULL_HIGH_PERF, see below
    private fun hold(on: Boolean, net: Boolean = false) {
        try {
            if (on) {
                if (wake == null) wake = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vlcinterface:playback").apply { setReferenceCounted(false) }
                wake?.acquire(4 * 60 * 60 * 1000L)
                if (net) {
                    // HIGH_PERF is marked deprecated but still keeps Wi-Fi awake for streaming; LOW_LATENCY (its
                    // replacement) disturbs Bluetooth audio on some phones, so it is kept on purpose.
                    if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                        .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vlcinterface:playback").apply { setReferenceCounted(false) }
                    wifi?.acquire()
                } else wifi?.takeIf { it.isHeld }?.release()
            } else {
                wake?.takeIf { it.isHeld }?.release()
                wifi?.takeIf { it.isHeld }?.release()
            }
        } catch (_: Exception) {}
    }

    /** The foreground start Android was promised, kept even when the service ends right away. */
    private fun promised() {
        val n = try { App.instance.media.build(App.instance.player.state.value) } catch (_: Throwable) {
            try { android.app.Notification.Builder(this, MediaCenter.CHANNEL).setSmallIcon(app.vlcinterface.R.drawable.ic_stat_cone)
                .setContentTitle("vlc interface").build() } catch (_: Throwable) { null } }
        if (n != null) try { startForeground(MediaCenter.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) } catch (_: Throwable) {}
    }

    private fun finish() {
        hold(false)
        App.instance.media.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        hold(false)
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_STOP = "stop"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_BACK = "back"
        const val ACTION_FWD = "fwd"
    }
}
