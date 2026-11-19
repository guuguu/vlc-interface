package app.vlcinterface.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Alarms are given to Android with setAlarmClock (exact, rings in Doze, shown as the system's next alarm) and set
 * again after a phone restart, a time / time zone change and an app update. A guard alarm checks every 90 s that a
 * ringing alarm is still alive (app killed → started again).
 */
object AlarmScheduler {
    const val ACTION_RING = "app.vlcinterface.alarm.RING"
    const val ACTION_GUARD = "app.vlcinterface.alarm.GUARD"
    const val ACTION_SNOOZE = "app.vlcinterface.alarm.SNOOZE"
    const val ACTION_AWAKE = "app.vlcinterface.alarm.AWAKE"
    const val ACTION_RESHOW = "app.vlcinterface.alarm.RESHOW"
    const val EXTRA_ID = "id"
    const val EXTRA_N = "n"
    const val EXTRA_SNOOZED = "snoozed"
    const val NEXT_ID = 7301
    const val MISSED_ID = 7302
    const val CH_NEXT = "alarm_next_quiet"

    /** Next ring time, or 0 when there is none (a dated single alarm whose date has passed). */
    fun nextTime(a: Alarm, from0: Long = System.currentTimeMillis()): Long {
        // Specific dates: the first one still to come.
        if (a.mode == "dates") return a.dates.mapNotNull { atDate(it, a) }.filter { it > from0 }.minOrNull() ?: 0L
        // Once: on its date, or the next time the clock shows that time.
        if (a.mode == "once") return if (a.date.isNotEmpty()) (atDate(a.date, a)?.takeIf { it > from0 } ?: 0L) else nextTime(a.copy(mode = "weekly", days = 0), from0)
        var from = from0
        // A date: nothing before that day.
        if (a.date.isNotEmpty()) try {
            val d = Calendar.getInstance(Alarms.zone()).apply {
                val p = a.date.split('-').map { it.toInt() }
                set(p[0], p[1] - 1, p[2], 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            if (a.days == 0) {
                d.set(Calendar.HOUR_OF_DAY, a.hour); d.set(Calendar.MINUTE, a.minute)
                return if (d.timeInMillis > from0) d.timeInMillis else 0L
            }
            if (d.timeInMillis - 1 > from) from = d.timeInMillis - 1
        } catch (_: Exception) {}
        val c = Calendar.getInstance(Alarms.zone()).apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute) }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.days != 0) {
            repeat(8) {
                val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday = 0
                if (a.days and (1 shl dow) != 0) return c.timeInMillis
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    /** The alarm's time on a date "yyyy-MM-dd", in the clock's time zone. */
    fun atDate(d: String, a: Alarm): Long? = try {
        val p = d.split('-').map { it.toInt() }
        Calendar.getInstance(Alarms.zone()).apply { set(p[0], p[1] - 1, p[2], a.hour, a.minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    } catch (_: Exception) { null }

    /** After an alarm rang (or was missed): one-time alarms switch off, passed dates drop out. */
    fun afterRing(a: Alarm, now: Long = System.currentTimeMillis()) {
        when (a.mode) {
            "weekly" -> return
            "dates" -> {
                val left = a.dates.filter { (atDate(it, a) ?: 0L) > now + 60_000 }
                Alarms.put(a.copy(dates = left, on = left.isNotEmpty() && a.on))
            }
            else -> Alarms.put(a.copy(on = false))
        }
    }

    private fun ringIntent(ctx: Context, id: String, snoozed: Boolean): PendingIntent =
        PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RING)
            .setData(Uri.parse("alarm://$id")).putExtra(EXTRA_ID, id).putExtra(EXTRA_SNOOZED, snoozed),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 7300,
        Intent(ctx, app.vlcinterface.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun canExact(ctx: Context): Boolean = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAt(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(ctx)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) { try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {} }
    }

    /** All alarms set again; a missed one (phone off, app updated) rings now or is announced. */
    fun reschedule(ctx: Context, checkMissed: Boolean = false) {
        val now = System.currentTimeMillis()
        val d = Alarms.current
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (a in d.alarms) {
            if (checkMissed && a.on) {
                val p = Alarms.planned(a.id)
                if (p in 1 until now - 60_000 && Alarms.lastRung(a.id) < p && Alarms.active.isEmpty()) {
                    Alarms.setRung(a.id, now)
                    if (d.global.missed == "ring30" && now - p < 30 * 60_000L) AlarmEngine.startRing(ctx, a.id, false)
                    else missedNote(ctx, a, p)
                    if (a.mode != "weekly") { afterRing(a, now); return }
                }
            }
            // A snooze set earlier keeps its own time.
            if (Alarms.planned(a.id) > now && snoozedUntil(ctx, a.id) > 0) continue
            val t = if (a.on) nextTime(a, now) else 0L
            if (t > 0) { Alarms.setPlanned(a.id, t); setAt(ctx, t, ringIntent(ctx, a.id, false)) }
            else { am.cancel(ringIntent(ctx, a.id, false)); Alarms.setPlanned(a.id, 0) }
        }
        updateNextNote(ctx)
        AlarmCalendar.sync(ctx)
    }

    fun snoozedUntil(ctx: Context, id: String): Long =
        ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE).getLong("snooze_$id", 0).takeIf { it > System.currentTimeMillis() } ?: 0

    fun snooze(ctx: Context, id: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putLong("snooze_$id", at).apply()
        Alarms.setPlanned(id, at)
        setAt(ctx, at, ringIntent(ctx, id, true))
        updateNextNote(ctx)
    }

    fun clearSnooze(ctx: Context, id: String) {
        ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().remove("snooze_$id").apply()
    }

    /** Guard: every 90 s while an alarm rings or is watched. */
    fun guard(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 7399, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_GUARD), PendingIntent.FLAG_IMMUTABLE)
        if (!on) { am.cancel(pi); return }
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 90_000, pi) }
        catch (_: Exception) { try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 90_000, pi) } catch (_: Exception) {} }
    }

    fun next(): Pair<Alarm, Long>? = Alarms.current.alarms.filter { it.on }.map { it to Alarms.planned(it.id).let { p ->
        if (p > System.currentTimeMillis()) p else nextTime(it) } }.filter { it.second > 0 }.minByOrNull { it.second }

    fun whenText(t: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).apply { timeZone = Alarms.zone() }.format(Date(t))

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_NEXT) == null) nm.createNotificationChannel(
            NotificationChannel(CH_NEXT, "Next alarm", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
    }

    fun updateNextNote(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = next()
        if (!Alarms.current.global.nextInNotif || n == null) { nm.cancel(NEXT_ID); return }
        channel(ctx)
        val (a, t) = n
        nm.notify(NEXT_ID, Notification.Builder(ctx, CH_NEXT).setSmallIcon(app.vlcinterface.R.drawable.ic_stat_cone)
            .setContentTitle("Next alarm: " + whenText(t)).setContentText(a.name.ifEmpty { Alarm.daysText(a.days) })
            .setOngoing(true).setShowWhen(false).setContentIntent(showIntent(ctx)).build())
    }

    private fun missedNote(ctx: Context, a: Alarm, t: Long) {
        channel(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(MISSED_ID, Notification.Builder(ctx, CH_NEXT)
            .setSmallIcon(app.vlcinterface.R.drawable.ic_stat_cone).setContentTitle("Missed alarm " + whenText(t))
            .setContentText(a.name.ifEmpty { "The phone was off or restarting" }).setAutoCancel(true).build())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // The app's start (App.onCreate) has loaded the alarms already.
        when (intent.action) {
            AlarmScheduler.ACTION_RING -> {
                val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
                AlarmScheduler.clearSnooze(ctx, id)
                AlarmEngine.startRing(ctx, id, intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZED, false))
            }
            AlarmScheduler.ACTION_GUARD -> AlarmEngine.guardCheck(ctx)
            AlarmScheduler.ACTION_SNOOZE -> AlarmEngine.snooze(ctx, intent.getIntExtra(AlarmScheduler.EXTRA_N, 0))
            AlarmScheduler.ACTION_AWAKE -> AlarmEngine.awake(ctx, "notification")
            AlarmScheduler.ACTION_RESHOW -> AlarmEngine.reshow(ctx)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                AlarmScheduler.reschedule(ctx, checkMissed = true)
                if (Alarms.active.isNotEmpty()) AlarmEngine.guardCheck(ctx)
            }
        }
    }
}
