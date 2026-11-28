package app.vlcinterface.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * "Short notification alarms" (2026-11-28 01:17): a second, simple alarm list for daily moments (pills, exercises …).
 * Not in the calendar, not in front of the player. At its time each one gives a short vibration and a short notification
 * sound once, and leaves a notification (heads-up on the screen and in the list, swiped away when you like).
 * Weekly on the chosen days, or one time only when no day is chosen. Switched on / off one by one.
 */
data class ShortAlarm(val id: String, val label: String = "", val hour: Int = 9, val minute: Int = 0, val days: Int = 0, val on: Boolean = true) {
    val timeText get() = "%02d:%02d".format(hour, minute)
    fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("h", hour).put("m", minute).put("d", days).put("on", on)
    companion object {
        fun of(o: JSONObject) = ShortAlarm(o.optString("id"), o.optString("label"), o.optInt("h", 9), o.optInt("m"), o.optInt("d"), o.optBoolean("on", true))
    }
}

/**
 * One setting for every short alarm (2026-11-28 13:22): the sound (any of the phone's sounds; "" = the default notification
 * sound, "none" = no sound), how long it plays (s), and the vibration (off / short / long / pulses for the sound's time).
 */
data class ShortAlarmSound(val uri: String = "", val seconds: Int = 3, val vibrate: String = "short") {
    val silent get() = uri == "none"
}

object ShortAlarms {
    const val ACTION = "app.vlcinterface.SHORT_ALARM"
    // A channel of its own without sound or vibration (13:22): the app plays the chosen sound for the chosen time and
    // vibrates itself; Android keeps a channel's sound fixed once made, so the old "short_alarm_notice" is removed.
    private const val CH = "short_alarm_notice2"
    val sound = MutableStateFlow(ShortAlarmSound())
    fun loadSound(ctx: Context) {
        val p = prefs(ctx)
        sound.value = ShortAlarmSound(p.getString("soundUri", "") ?: "", p.getInt("soundSec", 3).coerceIn(1, 60), p.getString("vibrate", "short") ?: "short")
    }
    fun setSound(ctx: Context, s: ShortAlarmSound) {
        sound.value = s
        prefs(ctx).edit().putString("soundUri", s.uri).putInt("soundSec", s.seconds).putString("vibrate", s.vibrate).apply()
    }
    /** The sound's name as the phone calls it. */
    fun soundName(ctx: Context, s: ShortAlarmSound = sound.value): String = when {
        s.silent -> "No sound"
        s.uri.isEmpty() -> "Default notification sound"
        else -> try { RingtoneManager.getRingtone(ctx, android.net.Uri.parse(s.uri))?.getTitle(ctx) ?: "Chosen sound" } catch (_: Exception) { "Chosen sound" }
    }

    /** Plays the chosen sound for its time and vibrates as chosen (also used by "Try"). */
    fun ring(ctx: Context, done: () -> Unit = {}) {
        loadSound(ctx)
        val s = sound.value
        val app = ctx.applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var rt: android.media.Ringtone? = null
        if (!s.silent) try {
            val u = if (s.uri.isEmpty()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else android.net.Uri.parse(s.uri)
            rt = RingtoneManager.getRingtone(app, u)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true     // a short sound repeats until its time is up
                play()
            }
        } catch (_: Exception) {}
        val ms = s.seconds * 1000L
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) app.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") app.getSystemService(android.os.Vibrator::class.java)
            val effect = when (s.vibrate) {
                "short" -> android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                "long" -> android.os.VibrationEffect.createOneShot(1500, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                "pulses" -> android.os.VibrationEffect.createWaveform(LongArray(((ms / 800).toInt().coerceAtLeast(1)) * 2) { i -> if (i % 2 == 0) 300L else 500L }.let { longArrayOf(0) + it }, -1)
                else -> null
            }
            if (effect != null) vib?.vibrate(effect)
        } catch (_: Exception) {}
        h.postDelayed({ try { rt?.stop() } catch (_: Exception) {}; done() }, ms)
    }
    val list = MutableStateFlow<List<ShortAlarm>>(emptyList())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("short_alarms", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        list.value = try { JSONArray(prefs(ctx).getString("list", "[]")).let { a -> (0 until a.length()).map { ShortAlarm.of(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    }
    private fun save(ctx: Context) { prefs(ctx).edit().putString("list", JSONArray().apply { list.value.forEach { put(it.json()) } }.toString()).apply() }

    fun put(ctx: Context, a: ShortAlarm) {
        list.value = list.value.filterNot { it.id == a.id } + a
        list.value = list.value.sortedWith(compareBy({ it.hour }, { it.minute }))
        save(ctx); schedule(ctx, a)
    }
    fun remove(ctx: Context, id: String) { cancel(ctx, id); list.value = list.value.filterNot { it.id == id }; save(ctx) }

    /** The next time [a] rings (weekly: its days; no day: the next time the clock shows its time). */
    fun next(a: ShortAlarm, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.days != 0) repeat(8) {
            // day bit 0 = Monday … 6 = Sunday (as the main alarms)
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (a.days and (1 shl dow) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun pi(ctx: Context, id: String) = PendingIntent.getBroadcast(ctx, ("short:" + id).hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context, a: ShortAlarm) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        cancel(ctx, a.id)
        if (!a.on) return
        val at = next(a)
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, a.id)) }
        catch (_: Exception) { try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, a.id)) } catch (_: Exception) {} }
    }
    fun cancel(ctx: Context, id: String) { try { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id)) } catch (_: Exception) {} }
    fun scheduleAll(ctx: Context) { load(ctx); list.value.forEach { schedule(ctx, it) } }

    /** Its time came: the notice (short sound and vibration, once), then the next time (weekly) or switched off (one time). */
    fun fire(ctx: Context, id: String) {
        if (list.value.isEmpty()) load(ctx)
        val a = list.value.firstOrNull { it.id == id } ?: return
        if (!a.on) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel("short_alarm_notice") } catch (_: Exception) {}
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Short notification alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, app.vlcinterface.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(("short:" + id).hashCode(), Notification.Builder(ctx, CH).setSmallIcon(app.vlcinterface.R.drawable.ic_stat_cone)
            .setContentTitle(a.label.ifEmpty { "Reminder" }).setContentText(a.timeText).setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).setShowWhen(true).build())
        if (a.days == 0) put(ctx, a.copy(on = false)) else schedule(ctx, a)
        ring(ctx)
    }
}
