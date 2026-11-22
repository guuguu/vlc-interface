package app.vlcinterface.player

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, OTHER }

data class OutputDevice(
    val key: String,
    /** AudioDeviceInfo id (0 when not connected). */
    val id: Int = 0,
    val name: String,
    val kind: OutputKind,
    val connected: Boolean,
    val active: Boolean,
    val detail: String,
)

/**
 * Lists sound outputs and tracks which one media is playing on.
 * Switching uses Android's own media output switcher (Android 14+), which can move this app's
 * playback between the phone and connected Bluetooth / wired devices.
 */
class OutputRouter(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _devices = MutableStateFlow<List<OutputDevice>>(emptyList())
    val devices: StateFlow<List<OutputDevice>> = _devices
    /** (before, after, picked by the user in the app) */
    var onActiveChanged: ((OutputDevice?, OutputDevice?, Boolean) -> Unit)? = null
    @Volatile private var userPick = false

    /**
     * Device picked in the app's Sound output sheet (null = follow Android). It stays until that device is
     * disconnected or another one is picked (2026-11-22 11:45: it was dropped twice for a Bluetooth device that was
     * still connected — Android's routing report flickering while our sound was moved, or the Bluetooth device being
     * re-announced with a new number). Kept across app restarts.
     */
    @Volatile private var chosen: String? = null
    private var lastRouted: String? = null
    /** The device number the sound engine plays to (re-applied when the chosen device comes back with a new number). */
    private var appliedId = 0
    /** Android's output switcher opened from the app: a change made there in the next 60 s counts as picked by hand. */
    private var switcherAt = 0L
    private val prefs = context.getSharedPreferences("output", Context.MODE_PRIVATE)

    private fun drop(why: String) {
        if (chosen == null) return
        try { app.vlcinterface.data.ServerLog.log("player", "output", "", "", true, "Sound output ${chosen!!.substringAfter(':')} released: $why") } catch (_: Exception) {}
        chosen = null; appliedId = 0; NativeAudio.device(0)
        prefs.edit().remove("chosen").apply()
    }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // A device just connected (Bluetooth, headphones): follow Android's choice again, like other players.
            // Only a real new output (Bluetooth, headphones, USB) that wasn't there before counts.
            // A device connecting no longer takes the sound away from the one picked in the app (11:45): the
            // picked one stays until it's disconnected or another is picked. (Following Android, nothing changes.)
            refresh()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Routing can change without any device being added or removed (e.g. Android's output switcher), so re-check often. */
    private val poller: Runnable = object : Runnable {
        override fun run() {
            try { refresh() } catch (_: Exception) {}
            handler.postDelayed(this, 1500)
        }
    }

    private var goneSince = 0L

    init {
        am.registerAudioDeviceCallback(callback, handler)
        // The device picked last time (kept across restarts), used again when it's connected.
        chosen = prefs.getString("chosen", null)
        refresh()
        handler.postDelayed(poller, 1500)
    }

    val active: OutputDevice? get() = _devices.value.firstOrNull { it.active }

    fun refresh() {
        val before = active
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val mediaAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        // Where Android sends media now. Android 13+ says it directly; on Android 12 the usual order is used:
        // Bluetooth, then USB, then wired, then the phone speaker.
        val routed: Set<Int> = if (android.os.Build.VERSION.SDK_INT >= 33)
            try { am.getAudioDevicesForAttributes(mediaAttrs).map { it.id }.toSet() } catch (e: Exception) { emptySet() }
        else {
            val order = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            order.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }?.let { setOf(it.id) } ?: emptySet()
        }

        val list = ArrayList<OutputDevice>()
        val seen = HashSet<String>()
        for (d in outs) {
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputKind.WIRED
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputKind.USB
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputKind.BLUETOOTH
                else -> null
            } ?: continue
            val name = when (kind) {
                OutputKind.SPEAKER -> "Phone speaker"
                OutputKind.WIRED -> "Wired headphones"
                OutputKind.USB -> d.productName?.toString()?.ifBlank { null } ?: "USB headphones"
                else -> d.productName?.toString()?.ifBlank { null } ?: "Bluetooth device"
            }
            val key = "${kind.name}:$name"
            if (!seen.add(key)) continue
            val detail = when (kind) {
                OutputKind.SPEAKER -> "Built-in"
                OutputKind.WIRED -> "Headphone jack · connected"
                OutputKind.USB -> "USB-C · connected"
                else -> "Bluetooth · connected"
            }
            list += OutputDevice(key, d.id, name, kind, true, d.id in routed, detail)
        }
        // Android's own routing changed (output switcher, quick settings): its choice wins over the app's.
        val routedKey = list.firstOrNull { it.active }?.key
        // (When our own sound plays on the chosen device, Android reports that device: that's not a change by Android.)
        // Only a change made in Android's output switcher opened from the app counts as a new pick; Android's own routing
        // report flickers while the app's sound moves between devices and is otherwise ignored.
        if (chosen != null && lastRouted != null && routedKey != lastRouted && routedKey != chosen &&
            System.currentTimeMillis() - switcherAt < 60_000) drop("changed in Android's output switcher")
        lastRouted = routedKey
        // Output chosen in the app (our own sound output plays straight to it): that's where the sound is.
        val chosenDev = chosen?.let { k -> list.firstOrNull { it.key == k && it.connected } }
        // The chosen device went away: a few seconds' grace (a Bluetooth reconnect, the phone re-announcing it), then
        // Android's default again.
        if (chosen != null && chosenDev == null) {
            if (goneSince == 0L) goneSince = System.currentTimeMillis()
            else if (System.currentTimeMillis() - goneSince > 6_000) drop("disconnected")
        } else goneSince = 0L
        // Same device, new number (reconnected): the sound engine follows it.
        if (chosenDev != null && chosenDev.id > 0 && chosenDev.id != appliedId && NativeAudio.active) { appliedId = chosenDev.id; NativeAudio.device(chosenDev.id) }
        if (chosenDev != null) for (i in list.indices) list[i] = list[i].copy(active = list[i].key == chosenDev.key)
        if (list.none { it.active }) {
            // Fallback: Bluetooth > wired > speaker, which is Android's usual media priority.
            val guess = list.firstOrNull { it.kind == OutputKind.BLUETOOTH } ?: list.firstOrNull { it.kind == OutputKind.WIRED || it.kind == OutputKind.USB } ?: list.firstOrNull()
            if (guess != null) list[list.indexOf(guess)] = guess.copy(active = true)
        }
        // Paired but not connected Bluetooth devices.
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                val bonded = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices ?: emptySet()
                for (b in bonded) {
                    val n = b.name ?: continue
                    if (list.any { it.kind == OutputKind.BLUETOOTH && it.name == n }) continue
                    list += OutputDevice("BLUETOOTH:$n", 0, n, OutputKind.BLUETOOTH, false, false, "Bluetooth · paired, not connected")
                }
            } catch (_: SecurityException) {
            }
        }
        list.sortBy { when (it.kind) { OutputKind.SPEAKER -> 0; OutputKind.WIRED, OutputKind.USB -> 1; OutputKind.BLUETOOTH -> if (it.connected) 2 else 3; else -> 4 } }
        if (list != _devices.value) _devices.value = list
        val after = active
        if (before?.key != after?.key) onActiveChanged?.invoke(before, after, userPick)
        userPick = false
    }

    /**
     * Plays on [d]. With the app's own sound output the app routes the sound itself, straight to that device
     * (speaker ↔ Bluetooth ↔ headphones, both ways, and even the speaker while headphones are connected).
     * With libVLC's output, Android's output switcher is opened as before.
     */
    fun select(d: OutputDevice) {
        if (!d.connected) { openBluetoothSettings(); return }
        if (NativeAudio.active && d.id > 0) {
            chosen = d.key; appliedId = d.id; goneSince = 0L
            prefs.edit().putString("chosen", d.key).apply()
            NativeAudio.device(d.id)
            userPick = true
            refresh()
        } else openSwitcher()
    }

    /**
     * True while the app plays on a device it picked itself and Android routes media elsewhere
     * (e.g. phone speaker chosen while Bluetooth is connected). Android's volume (buttons, swipe) then
     * changes the other device, so the app's own volume is used instead.
     */
    val overriding: Boolean get() = chosen != null && NativeAudio.active && chosen != lastRouted

    /** Opens Android's media output switcher for this app, or Bluetooth settings on older systems. */
    fun openSwitcher(): Boolean {
        switcherAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return true
            } catch (_: Exception) {
            }
        }
        openBluetoothSettings()
        return false
    }

    fun openBluetoothSettings() {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun volumePercent(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max == 0) 0 else am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(p: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (p.coerceIn(0, 100) * max / 100f).toInt(), 0)
    }
}
