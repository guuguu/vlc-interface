package app.vlcinterface.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import app.vlcinterface.App
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Radio and the phone's connection:
 *  - Wi-Fi drops while a station plays (and the phone falls back to mobile data): playback stops and a pop-up
 *    asks to exit or go on over mobile data (with lower-bitrate favourites suggested, and a warning about FLAC);
 *  - on mobile data, the data used by the current station is counted live.
 */
object NetGuard {
    /** The phone's internet goes over mobile data right now. */
    val cellular = MutableStateFlow(false)
    /** Show the "Wi-Fi lost" question. */
    val ask = MutableStateFlow(false)
    /** Allowed to play radio over mobile data (answered "Continue"); reset when Wi-Fi comes back. */
    @Volatile var mobileOk = false
    private var wifi = false
    private var startBytes = -1L
    private var startKey = ""

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun read(caps: NetworkCapabilities?) {
            val w = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val c = !w && caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val wasWifi = wifi
            wifi = w; cellular.value = c
            if (w) mobileOk = false
            if (wasWifi && !w) android.os.Handler(android.os.Looper.getMainLooper()).post { wifiLost() }
        }
        read(cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(caps)
                override fun onLost(network: Network) = read(cm.getNetworkCapabilities(cm.activeNetwork))
            })
        } catch (_: Exception) {}
    }

    private fun wifiLost() {
        val p = App.instance.player
        val s = p.state.value
        if (s.item?.isRadio != true || mobileOk) return
        p.pause()
        ask.value = true
    }

    /** "Continue on mobile data". */
    fun goOnMobile() { mobileOk = true; ask.value = false; App.instance.player.replayCurrent() }

    /** Data used by the playing station since it started on mobile data (bytes), or -1 when not on mobile data. */
    fun stationBytes(): Long {
        val s = App.instance.player.state.value
        val item = s.item
        if (!cellular.value || item?.isRadio != true) { startBytes = -1; startKey = ""; return -1 }
        val now = TrafficStats.getUidRxBytes(android.os.Process.myUid()).takeIf { it >= 0 } ?: return -1
        val key = item.uri
        if (key != startKey || startBytes < 0) { startKey = key; startBytes = now }
        return now - startBytes
    }

    /** "1.2 MB" style. */
    fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(java.util.Locale.US, b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    /** Bitrate written in a station's details ("128 kbps MP3 …"), 0 if unknown. */
    fun bitrateOf(meta: String): Int = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE).find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
