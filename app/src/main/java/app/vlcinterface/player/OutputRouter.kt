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

    /** Device picked in the app's Sound output sheet (null = follow Android). */
    @Volatile private var chosen: String? = null
    private var lastRouted: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // A device just connected (Bluetooth, headphones): follow Android's choice again, like other players.
            // Only a real new output (Bluetooth, headphones, USB) that wasn't there before counts.
            val known = _devices.value.filter { it.connected }.map { it.id }.toSet()
            val real = addedDevices?.any { it.isSink && it.id !in known && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE) } == true
            if (chosen != null && real) { chosen = null; NativeAudio.device(0) }
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

    init {
        am.registerAudioDeviceCallback(callback, handler)
        refresh()
        handler.postDelayed(poller, 1500)
    }

    val active: OutputDevice? get() = _devices.value.firstOrNull { it.active }

    fun refresh() {
        val before = active
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val mediaAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val routed = try { am.getAudioDevicesForAttributes(mediaAttrs).map { it.id }.toSet() } catch (e: Exception) { emptySet() }

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
        if (chosen != null && lastRouted != null && routedKey != lastRouted && routedKey != chosen) { chosen = null; NativeAudio.device(0) }
        lastRouted = routedKey
        // Output chosen in the app (our own sound output plays straight to it): that's where the sound is.
        val chosenDev = chosen?.let { k -> list.firstOrNull { it.key == k && it.connected } }
        if (chosen != null && chosenDev == null) { chosen = null; NativeAudio.device(0) }   // chosen device went away: Android default again
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
            chosen = d.key
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
