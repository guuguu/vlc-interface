package app.vlcinterface.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import app.vlcinterface.App
import app.vlcinterface.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps playback (radio, background audio) alive with a media notification. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = App.instance.player
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePause()
            ACTION_STOP -> { player.stop(); stopSelf(); return START_NOT_STICKY }
        }
        startForeground(ID, build(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (job == null) {
            job = scope.launch {
                player.state.collectLatest { st ->
                    if (st.item == null) { stopSelf(); return@collectLatest }
                    getSystemService(NotificationManager::class.java).notify(ID, build(st))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(st: PlayerState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val title = st.item?.title ?: "vlc interface"
        val text = if (st.nowPlaying.isNotEmpty()) st.nowPlaying else if (st.isPlaying) "Playing" else "Paused"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(st.isPlaying)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (st.isPlaying) "Pause" else "Play", action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", action(ACTION_STOP, 2)).build())
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val ID = 42
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_STOP = "stop"
    }
}
