package app.vlcinterface.player

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
    private val scope = CoroutineScope(Dispatchers.Main)
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
            ACTION_STOP -> { player.stop(); finish(); return START_NOT_STICKY }
        }
        val st = player.state.value
        if (st.item == null) { finish(); return START_NOT_STICKY }
        startForeground(MediaCenter.ID, app.media.build(st), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (job == null) {
            // Only react to "has item" / "is playing" changes; MediaCenter updates the notification content.
            job = scope.launch {
                player.state.map { (it.item != null) to it.isPlaying }.distinctUntilChanged().collect { (hasItem, playing) ->
                    when {
                        !hasItem -> finish()
                        playing -> startForeground(MediaCenter.ID, app.media.build(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                        else -> stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        App.instance.media.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
