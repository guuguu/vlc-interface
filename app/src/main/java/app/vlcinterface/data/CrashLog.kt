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
                    context() + "\n\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) {}
            // The radio buffer goes with the crash (also cleared at the next start).
            try { app.vlcinterface.sources.RadioCapture.wipeOnCrash(ctx) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        recordLastExit(ctx)
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
        } catch (_: Throwable) {}
    }
}
