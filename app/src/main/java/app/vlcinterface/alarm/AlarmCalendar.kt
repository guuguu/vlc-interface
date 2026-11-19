package app.vlcinterface.alarm

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.concurrent.Executors

/**
 * Alarms shown in the phone's calendar: written into one of Android's calendars (the account already signed in on
 * the phone, e.g. Google, which syncs it as usual). No account details pass through the app. Our events carry the
 * app's package as their owner so only those are ever changed; they have no reminders (the app rings).
 */
object AlarmCalendar {
    data class Cal(val id: Long, val name: String, val account: String, val primary: Boolean)

    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pending: java.util.concurrent.ScheduledFuture<*>? = null

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendars that can take events; the main account's own calendar first. */
    fun calendars(ctx: Context): List<Cal> {
        if (!allowed(ctx)) return emptyList()
        val out = ArrayList<Cal>()
        try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.ACCOUNT_TYPE),
                CalendarContract.Calendars.VISIBLE + "=1", null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val account = c.getString(2) ?: ""
                    val name = c.getString(1) ?: account
                    val primary = c.getInt(3) == 1 || (c.getString(5) == "com.google" && name == account)
                    out += Cal(c.getLong(0), name, account, primary)
                }
            }
        } catch (_: Exception) {}
        return out.sortedWith(compareBy<Cal>({ !it.primary }, { it.account }, { it.name }))
    }

    /** Brings the calendar in line with the alarms (in the background). */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        // Several changes in a row (sliders, several alarms): written once, 2 s after the last one.
        pending?.cancel(false)
        pending = worker.schedule({ try { syncNow(app) } catch (_: Exception) {} }, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun syncNow(ctx: Context) {
        if (!allowed(ctx)) return
        val cr = ctx.contentResolver
        val g = Alarms.current.global
        // Our own events only: removed, then written again for the alarms as they are now.
        cr.delete(CalendarContract.Events.CONTENT_URI, CalendarContract.Events.CUSTOM_APP_PACKAGE + "=?", arrayOf(ctx.packageName))
        if (!g.calSync || g.calId < 0) return
        val tz = Alarms.zone().id
        val now = System.currentTimeMillis()
        for (a in Alarms.current.alarms.filter { it.on && it.id != AlarmEngine.TEST_ID }) {
            val title = a.name.ifEmpty { "Alarm " + a.timeText }
            fun base(start: Long, key: String) = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, g.calId)
                put(CalendarContract.Events.TITLE, "⏰ $title")
                put(CalendarContract.Events.DESCRIPTION, "Alarm set in vlc interface")
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.HAS_ALARM, 0)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
                put(CalendarContract.Events.CUSTOM_APP_PACKAGE, ctx.packageName)
                put(CalendarContract.Events.CUSTOM_APP_URI, "vlcalarm:${a.id}:$key")
            }
            when (a.mode) {
                "weekly" -> {
                    val first = AlarmScheduler.nextTime(a, now)
                    if (first <= 0 || a.days == 0) continue
                    val byDay = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").filterIndexed { i, _ -> a.days and (1 shl i) != 0 }.joinToString(",")
                    cr.insert(CalendarContract.Events.CONTENT_URI, base(first, "w").apply {
                        put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;BYDAY=$byDay")
                        put(CalendarContract.Events.DURATION, "PT5M")
                    })
                }
                "dates" -> a.dates.mapNotNull { d -> AlarmScheduler.atDate(d, a)?.let { d to it } }.filter { it.second > now }.forEach { (d, t) ->
                    cr.insert(CalendarContract.Events.CONTENT_URI, base(t, d).apply { put(CalendarContract.Events.DTEND, t + 5 * 60_000) })
                }
                else -> {
                    val t = AlarmScheduler.nextTime(a, now)
                    if (t > 0) cr.insert(CalendarContract.Events.CONTENT_URI, base(t, "o").apply { put(CalendarContract.Events.DTEND, t + 5 * 60_000) })
                }
            }
        }
    }
}
