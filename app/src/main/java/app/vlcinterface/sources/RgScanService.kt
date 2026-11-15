package app.vlcinterface.sources

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.vlcinterface.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a ReplayGain scan running when the app is in the background (a long library scan) and shows its
 * progress; when it ends, a notification with the summary (tap → scan page with the report).
 */
class RgScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        startForeground(ID_PROGRESS, progress("Starting…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job == null) job = scope.launch {
            var last = 0L
            RgScanner.state.collectLatest { s ->
                if (s.finished) {
                    done(this@RgScanService, s)
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collectLatest
                }
                val now = System.currentTimeMillis()
                if (now - last > 1500) {
                    last = now
                    getSystemService(NotificationManager::class.java).notify(ID_PROGRESS,
                        progress("${s.phase}${if (s.current.isNotEmpty()) " · " + s.current else ""}", (s.progress * 100).toInt()))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    private fun progress(text: String, pct: Int): Notification = Notification.Builder(this, CH_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("ReplayGain scan $pct %")
        .setContentText(text)
        .setProgress(100, pct, false)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(open(this))
        .build()

    companion object {
        private const val CH_PROGRESS = "rgscan"
        private const val CH_DONE = "rgscan_done"
        private const val ID_PROGRESS = 51
        private const val ID_DONE = 52
        const val EXTRA_OPEN_RGSCAN = "open_rgscan"

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, RgScanService::class.java)) } catch (_: Exception) {}
        }

        private fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "ReplayGain scan progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "ReplayGain scan finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 52,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_RGSCAN, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Summary notification when the scan has ended. */
        fun done(ctx: Context, s: RgScanState) {
            channels(ctx)
            val text = "Saved ${s.saved} · skipped ${s.skipped}" + if (s.problems > 0) " · ${s.problems} problems — tap for the report" else " · no problems"
            ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, Notification.Builder(ctx, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (s.phase == "Cancelled") "ReplayGain scan stopped" else "ReplayGain scan finished")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open(ctx)).build())
        }
    }
}
