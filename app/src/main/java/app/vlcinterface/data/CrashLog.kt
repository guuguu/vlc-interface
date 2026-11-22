package app.vlcinterface.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Crash log (Settings → Logs → Crashes):
 *  - an app crash is written down at the moment it happens (what failed, where, the full trace, what was playing);
 *  - at the next start, Android's own record of why the app last ended is added: crash, native crash (in the
 *    sound / video code), "not responding", killed for memory, killed by the system… with its trace when Android
 *    keeps one. So also crashes the app itself could not catch show up.
 */
object CrashLog {
    const val KEY = "crashes"

    fun init(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                ServerLog.log(KEY, "crash", "app", "", false,
                    "Crash in ${t.name}: ${e.javaClass.simpleName}: ${e.message ?: ""}".take(300),
                    context() + "\nScreen: $screen" + appFrames(e) + "\n\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) {}
            // The radio buffer goes with the crash (also cleared at the next start).
            try { app.vlcinterface.sources.RadioCapture.wipeOnCrash(ctx) } catch (_: Throwable) {}
            notifyProblem(ctx, "crashed")
            previous?.uncaughtException(t, e)
        }
        recordLastExit(ctx)
    }

    /**
     * Safety net for the app's background work: an unexpected error inside one background task is written to
     * Settings → Logs → Crashes (as "Background error", with its trace) and that task stops — the app stays open
     * instead of closing.
     */
    val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        try {
            android.util.Log.e("vlcinterface", "background error", e)
            ServerLog.log(KEY, "error", "app", "", false,
                "Background error (app kept running): ${e.javaClass.simpleName}: ${e.message ?: ""}".take(300),
                context() + "\nScreen: $screen" + appFrames(e) + "\n\n" + android.util.Log.getStackTraceString(e))
        } catch (_: Throwable) {}
    }

    /** The page on screen (set by the navigation), for crash reports. */
    @Volatile var screen: String = ""

    /** The app's own code lines in the crash (often far down a long system stack), listed first. */
    private fun appFrames(e: Throwable): String {
        val frames = generateSequence(e) { it.cause }.flatMap { it.stackTrace.asSequence() }
            .filter { it.className.startsWith("app.vlcinterface") }.map { "  ${it.className.substringAfterLast('.')}.${it.methodName} (${it.fileName}:${it.lineNumber})" }
            .distinct().take(15).toList()
        return if (frames.isEmpty()) "\nApp code: none in the stack (inside a layout / drawing step)" else "\nApp code where it happened:\n" + frames.joinToString("\n")
    }

    /** What was going on (filled in by the app when available). */
    @Volatile var state: () -> String = { "" }
    private fun context(): String = try { state() } catch (_: Throwable) { "" }

    private fun recordLastExit(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val prefs = ctx.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val seen = prefs.getLong("lastExit", 0L)
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).filter { it.timestamp > seen }
            if (exits.isEmpty()) return
            prefs.edit().putLong("lastExit", exits.maxOf { it.timestamp }).apply()
            exits.sortedBy { it.timestamp }.forEach { x ->
                val why = when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "App crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (sound / video code)"
                    ApplicationExitInfo.REASON_ANR -> "App not responding"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: phone low on memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed: too much battery / CPU use"
                    ApplicationExitInfo.REASON_SIGNALED -> "Killed by the system (signal ${x.status})"
                    ApplicationExitInfo.REASON_FREEZER -> "Frozen by the system"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "Closed by you (force stop / swipe)"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "Closed itself (Exit)"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A part it depends on stopped"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission was changed"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
                    else -> "Ended (reason ${x.reason})"
                }
                // Normal endings (Exit, swipe away) are kept as a note; problems are marked as errors.
                val normal = x.reason == ApplicationExitInfo.REASON_EXIT_SELF || x.reason == ApplicationExitInfo.REASON_USER_REQUESTED
                val trace = try {
                    x.traceInputStream?.use { it.readBytes().decodeToString().take(60_000) } ?: ""
                } catch (_: Throwable) { "" }
                val detail = listOfNotNull(
                    x.description?.let { "Android says: $it" },
                    "Importance: ${x.importance} · memory ${x.pss / 1024} MB",
                    trace.ifEmpty { null },
                ).joinToString("\n\n")
                ServerLog.add(KEY, LogEntry(x.timestamp, "last exit", "android", "", normal, why, detail, 0))
            }
            // Crashes the app couldn't catch itself (native code, "not responding") get the small notice now.
            exits.lastOrNull { it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }?.let {
                notifyProblem(ctx, if (it.reason == ApplicationExitInfo.REASON_ANR) "stopped responding" else "crashed")   // app crashes: already shown when they happened
            }
        } catch (_: Throwable) {}
    }

    const val CH_PROBLEM = "app_problem"
    const val EXTRA_OPEN_LOGS = "open_logs"
    private const val PROBLEM_ID = 7400

    /**
     * Very small notice that the app crashed / stopped responding: one line, silent (no sound, vibration or pop-up),
     * a tap opens Settings → Logs. Always the same notice (replaced, never piled up).
     */
    fun notifyProblem(ctx: Context, what: String) {
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CH_PROBLEM) == null) nm.createNotificationChannel(
                android.app.NotificationChannel(CH_PROBLEM, "App problems", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null); enableVibration(false); enableLights(false); setShowBadge(false)
                })
            val open = android.content.Intent(ctx, app.vlcinterface.MainActivity::class.java)
                .putExtra(EXTRA_OPEN_LOGS, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = android.app.PendingIntent.getActivity(ctx, PROBLEM_ID, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(PROBLEM_ID, android.app.Notification.Builder(ctx, CH_PROBLEM)
                .setSmallIcon(app.vlcinterface.R.drawable.ic_stat_cone)
                .setContentTitle("vlc interface $what · check error log")
                .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(android.app.Notification.CATEGORY_ERROR)
                .build())
        } catch (_: Throwable) {}
    }
}
