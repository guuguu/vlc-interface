package app.vlcinterface.alarm

import app.vlcinterface.data.CrashLog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import app.vlcinterface.App
import app.vlcinterface.R
import app.vlcinterface.ui.knownMusicRoot
import app.vlcinterface.player.NativeAudio
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.OutputKind
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** What the ringing screen shows. phase: "idle", "ringing", "snoozed", "watching", "confirming". */
data class AlarmUi(
    val phase: String = "idle",
    val alarmId: String = "",
    val name: String = "",
    val snoozesLeft: Int = 0,
    val until: Long = 0,
)

/**
 * Rings an alarm: the chosen music through the app's player (volume start → target with the crescendo), vibration,
 * light; a watchdog switches to phone songs and then to the alarm tone when nothing plays. After a stop by hand, the
 * wake check watches for signs of being awake (movement, unlocking, network or place changes…); none for the set
 * minutes → a silent, vibrating "Are you awake?" for 30 s → no movement → rings again at once, without crescendo.
 */
object AlarmEngine {
    const val NOTIF_ID = 7310
    /** "Test alarm in 1 minute". */
    const val TEST_ID = "test"
    const val SNOOZE_NOTIF_ID = 7311
    private const val CH_RING = "alarm_ring_quiet"
    private const val CH_RING_LED = "alarm_ring_led_quiet"
    private const val CH_WATCH = "alarm_watch_quiet"

    private val _ui = MutableStateFlow(AlarmUi())
    val ui: StateFlow<AlarmUi> = _ui
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.guard)
    private var ringJob: Job? = null
    private var fxJob: Job? = null
    private var watchJob: Job? = null
    private var tone: MediaPlayer? = null
    private var prevVolume = -1
    private var prevAlarmVolume = -1
    /** Volume buttons set to "volume": the crescendo stops following its curve. */
    @Volatile var userVolume = false
    private lateinit var app: Context

    private fun log(s: String) { try { ServerLog.log("alarm", "alarm", "", "", true, s) } catch (_: Exception) {} }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ start

    /** From the alarm (receiver), a missed alarm, or the guard: hands over to the foreground service. */
    fun startRing(ctx: Context, id: String, snoozed: Boolean, restart: Boolean = false) {
        app = ctx.applicationContext
        val a = Alarms.get(id) ?: return
        if (_ui.value.phase == "ringing" && _ui.value.alarmId == id && AlarmService.running) return
        if (!snoozed && !restart) prefs(ctx).edit().putInt("snoozes_$id", 0).apply()
        Alarms.setRung(id, System.currentTimeMillis())
        Alarms.active = id; Alarms.activePhase = "ringing"
        log("ring ${a.timeText} ${a.name}" + if (snoozed) " (snooze)" else if (restart) " (wake check: rings again)" else "")
        try {
            app.startForegroundService(Intent(app, AlarmService::class.java).setAction(AlarmService.RING)
                .putExtra("id", id).putExtra("restart", restart).putExtra("snoozed", snoozed && !restart))
        } catch (e: Exception) { log("service: ${e.message}") }
        if (!snoozed && !restart) { if (a.mode != "weekly") AlarmScheduler.afterRing(a) else AlarmScheduler.reschedule(app) }
    }

    /** Inside the service (foreground already). */
    fun ring(ctx: Context, id: String, restart: Boolean, snoozed: Boolean = false) {
        app = ctx.applicationContext
        val a = Alarms.get(id) ?: run { finishAll(); return }
        stopWatch()
        stopEffects()
        userVolume = false
        val left = if (a.maxSnoozes < 0) 99 else (a.maxSnoozes - prefs(app).getInt("snoozes_$id", 0)).coerceAtLeast(0)
        _ui.value = AlarmUi("ringing", id, a.name.ifEmpty { "Alarm " + a.timeText }, left)
        Alarms.active = id; Alarms.activePhase = "ringing"
        refreshNotif()
        showScreen()
        AlarmScheduler.guard(app, true)
        ringJob?.cancel()
        ringJob = scope.launch { ringBody(a, restart, snoozed) }
    }

    private suspend fun ringBody(a: Alarm, restart: Boolean, snoozed: Boolean) {
        val g = Alarms.current.global
        val am = app.getSystemService(AudioManager::class.java)
        if (g.callWait) {
            var waited = 0
            while (inCall(am) && waited < 30 * 60) { delay(5000); waited += 5 }
        }
        val router = App.instance.output
        if (prevVolume < 0) prevVolume = router.volumePercent()
        if (prevAlarmVolume < 0) prevAlarmVolume = streamPct(am, AudioManager.STREAM_ALARM)
        startEffects(g, a.vibrate ?: g.vibrate, a.led ?: g.led)
        val player = App.instance.player
        val low = battery() in 0..15 && g.lowBattery == "tone"
        // Music or radio already playing: stopped completely, only the phone's alarm tone rings.
        val wasPlaying = player.state.value.isPlaying && !restart && !snoozed
        if (wasPlaying) { try { player.pause() } catch (_: Exception) {}; log("music was playing: stopped, alarm tone only") }
        selectOutput(a)
        // What plays, in order: phone music (root folder) → alarm sound; preferred station → fallback station →
        // alarm sound; each step taken when nothing is heard for 15 s.
        val steps = when {
            low || wasPlaying || a.source == "tone" -> listOf("tone")
            a.source == "radio" -> listOfNotNull(if (a.stationUrl.isNotEmpty()) "radio1" else null, if (a.fallbackUrl.isNotEmpty()) "radio2" else null, "tone")
            else -> listOf("local", "tone")
        }
        var step = 0
        if (steps[0] == "tone") tonePending = true
        var vol = if (restart) a.volTarget else a.volStart
        setVol(am, vol)
        if (!startStep(steps[0], a, snoozed)) { step = steps.lastIndex; tonePending = true; setVol(am, vol); startTone() }
        val t0 = System.currentTimeMillis()
        var quietSince = 0L
        while (scope.isActive && _ui.value.phase == "ringing") {
            val el = (System.currentTimeMillis() - t0) / 1000f
            if (!userVolume) {
                val f = if (restart || a.fadeSec <= 0) 1f else (el / a.fadeSec).coerceIn(0f, 1f)
                val c = when (g.crescendo) { "gentle" -> f.pow(2f); "fast" -> sqrt(f); else -> f }
                val v = (a.volStart + (a.volTarget - a.volStart) * c).toInt()
                if (v != vol) { vol = v; setVol(am, v) }
            }
            // Watchdog: nothing heard for 15 s → the next step.
            if (steps[step] != "tone") {
                if (!player.state.value.isPlaying) {
                    if (quietSince == 0L) quietSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - quietSince > 15_000) {
                        quietSince = 0L
                        step++
                        log("nothing plays: " + when (steps[step]) { "radio2" -> "fallback station"; "tone" -> "alarm sound"; else -> steps[step] })
                        if (steps[step] == "tone" || !startStep(steps[step], a, snoozed)) {
                            step = steps.lastIndex
                            try { player.pause() } catch (_: Exception) {}; tonePending = true; setVol(am, vol); startTone()
                        }
                    }
                } else quietSince = 0L
            }
            if (a.stopAfterMin > 0 && el > a.stopAfterMin * 60) { log("stopped by itself after ${a.stopAfterMin} min"); autoStop(); return }
            // Every 30 s: posted again only if it is gone (cleared), so the screen can always be reached — never
            // re-posted while it is shown (that would make the notification list jump).
            if ((el * 4).toInt() % 120 == 0 && el > 1f && !notifShown()) refreshNotif(force = true)
            delay(250)
        }
    }

    private fun inCall(am: AudioManager) = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE

    private fun battery(): Int = try { app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }

    private fun streamPct(am: AudioManager, s: Int): Int { val m = am.getStreamMaxVolume(s); return if (m == 0) 0 else am.getStreamVolume(s) * 100 / m }

    private fun setVol(am: AudioManager, pct0: Int) {
        val pct = pct0.coerceAtMost(Alarms.current.global.maxLevel.coerceIn(1, 100))
        try {
            if (tone != null || tonePending) { val m = am.getStreamMaxVolume(AudioManager.STREAM_ALARM); am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(pct * m / 100f).coerceAtLeast(1), 0) }
            else App.instance.output.setVolumePercent(pct)
        } catch (_: Exception) {}
    }

    private fun selectOutput(a: Alarm) {
        if (a.output.isEmpty()) return
        try {
            val r = App.instance.output
            r.refresh()
            val d = r.devices.value.firstOrNull { if (a.output == "speaker") it.kind == OutputKind.SPEAKER else it.key == a.output } ?: return
            if (d.connected && !d.active && NativeAudio.active && d.id > 0) r.select(d)
        } catch (_: Exception) {}
    }

    @Volatile private var tonePending = false

    /** Starts one step; false = can't (nothing to play there). */
    private suspend fun startStep(step: String, a: Alarm, snoozed: Boolean): Boolean {
        val player = App.instance.player
        return when (step) {
            "radio1", "radio2" -> {
                val url = if (step == "radio1") a.stationUrl else a.fallbackUrl
                val name = if (step == "radio1") a.stationName else a.fallbackName
                if (url.isEmpty()) false else {
                    player.alarmLive = true   // live: no network buffer
                    player.playQueue(listOf(PlayItem(url, name.ifEmpty { "Radio" }, isRadio = true)), 0, Origin("radio", "", url, name), keepOrder = true)
                    true
                }
            }
            "local" -> {
                // Chosen folder, else the one the audio player uses, else all music on the phone.
                val g = Alarms.current.global
                val (root, vol) = if (g.localRoot.isNotEmpty()) g.localRoot to g.localVolume else knownMusicRoot() ?: ("" to "")
                playLocal(root, vol).also { if (it && snoozed) otherPart() }
            }
            else -> false
        }
    }

    /** After a snooze: a different song, from a different part of it. */
    private suspend fun otherPart() {
        val player = App.instance.player
        var waited = 0
        while (!player.state.value.isPlaying && waited < 10_000) { delay(250); waited += 250 }
        val s = player.state.value
        if (s.queue.size > 1) {
            val i = (s.queue.indices - s.index).filter { s.queue[it].isMusic }.randomOrNull() ?: return
            player.jumpTo(i)
            waited = 0
            delay(800)
            while ((!player.state.value.isPlaying || player.state.value.length <= 0) && waited < 10_000) { delay(250); waited += 250 }
        }
        if (player.state.value.length > 30_000) player.seekFraction((15..70).random() / 100f)
    }

    /** Phone songs under the music root folder (no network), in random order. */
    private suspend fun playLocal(root: String, volume: String): Boolean {
        val items = withContext(Dispatchers.IO) {
            val out = ArrayList<PlayItem>()
            try {
                app.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION),
                    MediaStore.Audio.Media.IS_MUSIC + "!=0" + (if (root.isNotEmpty()) " AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?" +
                        (if (volume.isNotEmpty()) " AND " + MediaStore.MediaColumns.VOLUME_NAME + "=?" else "") else ""),
                    if (root.isEmpty()) null else listOfNotNull("$root%", volume.ifEmpty { null }).toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                        out += PlayItem(uri, c.getString(1) ?: "", artist = c.getString(3) ?: "", album = c.getString(4) ?: "",
                            durationMs = c.getLong(5), fileName = c.getString(2) ?: "")
                    }
                }
            } catch (_: Exception) {}
            out.filter { it.isMusic }.shuffled().take(80)
        }
        if (items.isEmpty()) return false
        App.instance.player.playQueue(items, 0, Origin("single", title = "Alarm: phone songs"), keepOrder = true)
        return true
    }

    private fun startTone() {
        if (tone != null) return
        val uri = Alarms.current.global.toneUri.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            tone = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, uri); isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { log("tone: ${e.message}"); tone = null }
    }

    // ------------------------------------------------------------------ vibration / light

    private fun startEffects(g: AlarmGlobal, vib: Boolean, led: String) {
        if (vib) vibrate(longArrayOf(0, 700, 700))
        fxJob?.cancel()
        if (led == "flash") fxJob = scope.launch {
            val cm = app.getSystemService(CameraManager::class.java)
            val id = try { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } catch (_: Exception) { null }
                ?: return@launch
            var on = false
            try { while (isActive) { on = !on; cm.setTorchMode(id, on); delay(500) } }
            finally { try { cm.setTorchMode(id, false) } catch (_: Exception) {} }
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            app.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) {}
    }

    private fun stopEffects() {
        fxJob?.cancel(); fxJob = null
        try { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() } catch (_: Exception) {}
        tone?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        tone = null
    }

    private fun stopSound() {
        ringJob?.cancel(); ringJob = null
        tonePending = false
        try { App.instance.player.alarmLive = false } catch (_: Exception) {}
        stopEffects()
        try { App.instance.player.pause() } catch (_: Exception) {}
        restoreVolume()
    }

    private fun restoreVolume() {
        val am = app.getSystemService(AudioManager::class.java)
        if (prevVolume >= 0) try { App.instance.output.setVolumePercent(prevVolume) } catch (_: Exception) {}
        if (prevAlarmVolume >= 0) try { val m = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(prevAlarmVolume * m / 100f), 0) } catch (_: Exception) {}
        prevVolume = -1; prevAlarmVolume = -1
    }

    // ------------------------------------------------------------------ user actions

    /** Snooze with preset [n] (0, 1, 2). */
    fun snooze(ctx: Context, n: Int) {
        app = ctx.applicationContext
        val u = _ui.value
        if (u.phase != "ringing" || u.snoozesLeft <= 0) return
        val a = Alarms.get(u.alarmId) ?: return
        val min = Alarms.current.global.snoozes.getOrElse(n) { 5 }.coerceAtLeast(1)
        prefs(app).edit().putInt("snoozes_${a.id}", prefs(app).getInt("snoozes_${a.id}", 0) + 1).apply()
        stopSound()
        AlarmScheduler.snooze(app, a.id, min)
        val until = System.currentTimeMillis() + min * 60_000L
        _ui.value = u.copy(phase = "snoozed", until = until)
        Alarms.activePhase = "snoozed"
        log("snooze $min min")
        snoozeNote(until, u.name)
        AlarmScheduler.guard(app, false)
        stopService()
    }

    /** Stop (after the pattern). Also dismisses a snoozed alarm. */
    fun stop(ctx: Context) {
        app = ctx.applicationContext
        val u = _ui.value
        val id = u.alarmId.ifEmpty { Alarms.active }
        stopSound()
        if (id.isNotEmpty()) { AlarmScheduler.clearSnooze(app, id); AlarmScheduler.reschedule(app) }
        app.getSystemService(NotificationManager::class.java).cancel(SNOOZE_NOTIF_ID)
        log("stopped by hand")
        val mins = Alarms.current.global.wakeCheckMin
        if (mins > 0 && id.isNotEmpty() && u.phase != "snoozed") startWatch(id, System.currentTimeMillis() + mins * 60_000L)
        else finishAll()
    }

    private fun autoStop() {
        stopSound()
        finishAll()
    }

    /** A sign of being awake: the wake check ends. */
    fun awake(ctx: Context, why: String) {
        app = ctx.applicationContext
        val p = _ui.value.phase
        if (p != "watching" && p != "confirming") return
        log("awake: $why")
        finishAll()
    }

    private fun finishAll() {
        if (_ui.value.alarmId == TEST_ID || Alarms.active == TEST_ID) try { Alarms.delete(TEST_ID) } catch (_: Exception) {}
        stopWatch()
        stopEffects()
        ringJob?.cancel(); ringJob = null
        _ui.value = AlarmUi()
        Alarms.active = ""; Alarms.activePhase = ""
        prefs(app).edit().remove("watchUntil").apply()
        AlarmScheduler.guard(app, false)
        stopService()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun stopService() { try { app.stopService(Intent(app, AlarmService::class.java)) } catch (_: Exception) {} }

    /** Notification swiped away: posted again, and the alarm screen shown. */
    fun reshow(ctx: Context) {
        app = ctx.applicationContext
        if (_ui.value.phase == "idle") return
        refreshNotif(force = true)
        if (_ui.value.phase == "ringing" || _ui.value.phase == "confirming") showScreen()
    }

    /** Guard alarm / app restarted: an alarm that should ring (or be watched) is taken up again. */
    fun guardCheck(ctx: Context) {
        app = ctx.applicationContext
        val id = Alarms.active
        if (id.isEmpty()) { AlarmScheduler.guard(app, false); return }
        // A ringing alarm interrupted long ago (phone restarted, battery out): over — not taken up again.
        val rung = Alarms.lastRung(id)
        val limit = ((Alarms.get(id)?.stopAfterMin ?: 30).takeIf { it > 0 } ?: 60) * 60_000L
        if (Alarms.activePhase == "ringing" && rung > 0 && System.currentTimeMillis() - rung > limit) {
            log("interrupted alarm older than its ringing time: not rung again"); finishAll(); return
        }
        when (Alarms.activePhase) {
            "ringing" -> if (!AlarmService.running || _ui.value.phase != "ringing") startRing(app, id, snoozed = true, restart = true)
            "watching", "confirming" -> if (_ui.value.phase != "watching" && _ui.value.phase != "confirming") {
                val until = prefs(app).getLong("watchUntil", 0)
                if (until > 0) startWatch(id, until.coerceAtLeast(System.currentTimeMillis() + 30_000)) else finishAll()
            }
            "snoozed" -> if (AlarmScheduler.snoozedUntil(app, id) == 0L) { Alarms.active = ""; Alarms.activePhase = "" }
                else _ui.value = AlarmUi("snoozed", id, Alarms.get(id)?.name ?: "", 0, AlarmScheduler.snoozedUntil(app, id))
        }
        if (Alarms.active.isNotEmpty() && Alarms.activePhase != "snoozed") AlarmScheduler.guard(app, true)
    }

    // ------------------------------------------------------------------ wake check

    private var sensorListener: SensorEventListener? = null
    private var proofReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var locListener: LocationListener? = null
    private val activeSeconds = HashSet<Long>()

    private fun startWatch(id: String, until: Long) {
        val a = Alarms.get(id)
        _ui.value = AlarmUi("watching", id, a?.name?.ifEmpty { null } ?: ("Alarm " + (a?.timeText ?: "")), 0, until)
        Alarms.active = id; Alarms.activePhase = "watching"
        prefs(app).edit().putLong("watchUntil", until).apply()
        try { app.startForegroundService(Intent(app, AlarmService::class.java).setAction(AlarmService.UPDATE)) } catch (_: Exception) {}
        refreshNotif()
        AlarmScheduler.guard(app, true)
        log("wake check until " + AlarmScheduler.whenText(until))
        activeSeconds.clear()
        listenSensors(needed = 3)
        listenProofs()
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && System.currentTimeMillis() < until) delay(1000)
            if (_ui.value.phase == "watching") confirm(id)
        }
    }

    /** No sign of being awake: a silent "Are you awake?" that only vibrates, 30 s with the motion sensor watching. */
    private suspend fun confirm(id: String) {
        _ui.value = _ui.value.copy(phase = "confirming", until = System.currentTimeMillis() + 30_000)
        Alarms.activePhase = "confirming"
        refreshNotif()
        showScreen()
        vibrate(longArrayOf(0, 400, 600))
        activeSeconds.clear()
        listenSensors(needed = 1)
        while (System.currentTimeMillis() < _ui.value.until) { delay(500); if (_ui.value.phase != "confirming") return }
        stopWatch()
        stopEffects()
        log("no movement for 30 s: rings again")
        startRing(app, id, snoozed = true, restart = true)
    }

    private fun listenSensors(needed: Int) {
        val sm = app.getSystemService(SensorManager::class.java)
        sensorListener?.let { sm.unregisterListener(it) }
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val s = gyro ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val moving = if (gyro != null) mag > 0.6f else abs(mag - SensorManager.GRAVITY_EARTH) > 1.5f
                if (moving) {
                    activeSeconds += System.currentTimeMillis() / 1000
                    if (activeSeconds.size >= needed) awake(app, "movement")
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensorListener = l
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun listenProofs() {
        if (proofReceiver != null) return
        val started = System.currentTimeMillis()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (isInitialStickyBroadcast) return
                awake(app, when (i.action) { Intent.ACTION_USER_PRESENT -> "phone unlocked"; Intent.ACTION_HEADSET_PLUG -> "headset"; else -> "charger" })
            }
        }
        proofReceiver = r
        val f = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_HEADSET_PLUG) }
        try { if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f) } catch (_: Exception) {}
        // Network: Wi-Fi network changed, Wi-Fi ↔ mobile, connection lost.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        var base: Network? = cm.activeNetwork
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (System.currentTimeMillis() - started < 3000 && base == null) { base = n; return }
                if (n != base) scope.launch { awake(app, "network changed") }
            }
            override fun onLost(n: Network) { if (n == base && System.currentTimeMillis() - started > 3000) scope.launch { awake(app, "network lost") } }
        }
        netCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
        // Place: moved more than 150 m.
        if (Alarms.current.global.useLocation && (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            val lm = app.getSystemService(LocationManager::class.java)
            var first: Location? = null
            val ll = LocationListener { loc ->
                val f0 = first
                if (f0 == null) first = loc
                else if (loc.distanceTo(f0) > 150f + loc.accuracy) awake(app, "phone moving")
            }
            locListener = ll
            val prov = if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
            try { lm.requestLocationUpdates(prov, 20_000L, 0f, ll, Looper.getMainLooper()) } catch (_: Exception) {}
        }
    }

    private fun stopWatch() {
        watchJob?.cancel(); watchJob = null
        if (!::app.isInitialized) return
        sensorListener?.let { try { app.getSystemService(SensorManager::class.java).unregisterListener(it) } catch (_: Exception) {} }
        sensorListener = null
        proofReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        proofReceiver = null
        netCallback?.let { try { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        locListener?.let { try { app.getSystemService(LocationManager::class.java).removeUpdates(it) } catch (_: Exception) {} }
        locListener = null
    }

    // ------------------------------------------------------------------ notifications / screen

    private fun channels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_RING) == null) nm.createNotificationChannel(NotificationChannel(CH_RING, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH)
            .apply { setSound(null, null); enableVibration(false); setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        if (nm.getNotificationChannel(CH_RING_LED) == null) nm.createNotificationChannel(NotificationChannel(CH_RING_LED, "Alarm ringing (light)", NotificationManager.IMPORTANCE_HIGH)
            .apply { setSound(null, null); enableVibration(false); enableLights(true); lightColor = Color.YELLOW; setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        if (nm.getNotificationChannel(CH_WATCH) == null) nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Alarm wake check", NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null); enableVibration(false) })
    }

    private fun screenIntent(): PendingIntent = PendingIntent.getActivity(app, 7312,
        Intent(app, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun bc(action: String, code: Int, n: Int = 0): PendingIntent = PendingIntent.getBroadcast(app, code,
        Intent(app, AlarmReceiver::class.java).setAction(action).putExtra(AlarmScheduler.EXTRA_N, n), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notification(ctx: Context): Notification {
        app = ctx.applicationContext
        channels()
        val u = _ui.value
        val g = Alarms.current.global
        val urgent = u.phase == "ringing" || u.phase == "confirming"
        val b = Notification.Builder(app, if (!urgent) CH_WATCH else if ((Alarms.get(u.alarmId)?.led ?: g.led) == "notif") CH_RING_LED else CH_RING)
            .setSmallIcon(R.drawable.ic_stat_cone).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setSortKey("0alarm").setGroup("alarm")
            .setCategory(Notification.CATEGORY_ALARM).setContentIntent(screenIntent()).setVisibility(Notification.VISIBILITY_PUBLIC)
            // Swiped away (Android 14+ allows it): it comes straight back, with the alarm screen.
            .setDeleteIntent(bc(AlarmScheduler.ACTION_RESHOW, 7331))
        when (u.phase) {
            "ringing" -> {
                b.setContentTitle(u.name.ifEmpty { "Alarm" }).setContentText("Tap to stop").setFullScreenIntent(screenIntent(), true)
                if (g.lockActions && u.snoozesLeft > 0) g.snoozes.forEachIndexed { i, m ->
                    b.addAction(Notification.Action.Builder(null, "Snooze $m min", bc(AlarmScheduler.ACTION_SNOOZE, 7320 + i, i)).build()) }
            }
            "confirming" -> b.setContentTitle("Are you awake?").setContentText("Tap: I'm awake").setFullScreenIntent(screenIntent(), true)
                .addAction(Notification.Action.Builder(null, "I'm awake", bc(AlarmScheduler.ACTION_AWAKE, 7330)).build())
            "watching" -> b.setContentTitle("Wake check until " + AlarmScheduler.whenText(u.until).substringAfter(' '))
                .setContentText("Rings again if there's no sign of you being awake")
                .addAction(Notification.Action.Builder(null, "I'm awake", bc(AlarmScheduler.ACTION_AWAKE, 7330)).build())
            else -> b.setContentTitle("Alarm")
        }
        return b.build()
    }

    private var lastNotifKey = ""

    private fun notifShown(): Boolean = try {
        app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIF_ID }
    } catch (_: Exception) { true }

    /** Posted only when what it shows changes (or [force]: it was cleared) — no needless updates that reorder the list. */
    private fun refreshNotif(force: Boolean = false) {
        val u = _ui.value
        val key = "${u.phase}|${u.name}|${u.snoozesLeft}|${u.until}"
        if (!force && key == lastNotifKey && notifShown()) return
        lastNotifKey = key
        try { app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(app)) } catch (_: Exception) {}
    }

    private fun snoozeNote(until: Long, name: String) {
        channels()
        app.getSystemService(NotificationManager::class.java).notify(SNOOZE_NOTIF_ID, Notification.Builder(app, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_cone).setContentTitle("Snoozed until " + AlarmScheduler.whenText(until).substringAfter(' '))
            .setContentText(name.ifEmpty { "Tap to dismiss the alarm" }).setOngoing(true).setContentIntent(screenIntent()).build())
    }

    private fun showScreen() {
        try { app.startActivity(Intent(app, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)) } catch (_: Exception) {}
    }
}

/** Keeps the app alive (foreground) while an alarm rings or the wake check runs. */
class AlarmService : Service() {
    companion object {
        const val RING = "ring"
        const val UPDATE = "update"
        @Volatile var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        try { startForeground(AlarmEngine.NOTIF_ID, AlarmEngine.notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        catch (_: Exception) {}
        when (intent?.action) {
            RING -> AlarmEngine.ring(this, intent.getStringExtra("id") ?: "", intent.getBooleanExtra("restart", false), intent.getBooleanExtra("snoozed", false))
            UPDATE -> {}
            null -> AlarmEngine.guardCheck(this)   // restarted by Android after being killed
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

/** Test alarm: rings in 1 minute with the settings of the first alarm (or the alarm sound), also after Exit. */
fun testAlarm(ctx: Context) {
    val base = Alarms.current.alarms.firstOrNull { it.id != AlarmEngine.TEST_ID }
    Alarms.put((base ?: Alarm()).copy(id = AlarmEngine.TEST_ID, on = false, name = "Test alarm", days = 0, stopAfterMin = 5, maxSnoozes = 0))
    AlarmScheduler.snooze(ctx.applicationContext, AlarmEngine.TEST_ID, 1)
}
