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
    private var online = false
    private var startBytes = -1L
    private var startKey = ""

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun read(caps: NetworkCapabilities?) {
            val w = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val c = !w && caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val wasWifi = wifi
            val on = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val wasOn = online
            // Wi-Fi → mobile data, or the connection dropped: the radio recording is held — kept if the connection is
            // back within the radio buffer and nothing is missing, else deleted. Wi-Fi back: decided / started again.
            if ((wasWifi && !w) || (wasOn && !on)) try { app.vlcinterface.sources.RadioCapture.netLost() } catch (_: Exception) {}
            if (w && on && (!wasWifi || !wasOn)) try { app.vlcinterface.sources.RadioCapture.netBack() } catch (_: Exception) {}
            online = on
            wifi = w; cellular.value = c
            if (w) mobileOk = false
            if (wasWifi && !w) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ wifiLost() }, 800)
            if (!wasWifi && w) android.os.Handler(android.os.Looper.getMainLooper()).post { wifiBack() }
        }
        read(cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(caps)
                override fun onLost(network: Network) = read(cm.getNetworkCapabilities(cm.activeNetwork))
            })
        } catch (_: Exception) {}
    }

    /** What was stopped when Wi-Fi went (shown in the pop-up). */
    val stopped = MutableStateFlow<List<String>>(emptyList())
    /** The player was stopped by the Wi-Fi-lost pop-up (not by you). */
    @Volatile var stoppedPlayback = false; private set
    private var pausedWork = false

    /** On mobile data and not allowed there: anything that needs internet waits. */
    fun blocked(): Boolean = cellular.value && !mobileOk

    /**
     * Needs internet: radio and web addresses (http, https, WebDAV, FTP), and network shares on hosts outside the
     * home network. Phone files and LAN shares (private addresses, .local, plain names) don't.
     */
    fun needsInternet(uri: String): Boolean {
        val u = uri.lowercase()
        if (u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/")) return false
        if (Regex("^(https?|ftps?|dav|davs|mms|rtsp|rtmp)://").containsMatchIn(u)) return true
        val host = android.net.Uri.parse(uri).host ?: return false
        return !(Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|127\\.|169\\.254\\.)").containsMatchIn(host) ||
            host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || !host.contains('.'))
    }

    /**
     * Wi-Fi gone, mobile data in use: what needs internet stops (playback of a station / web file, ReplayGain scans
     * and indexing of internet files, radio recording, picture and link look-ups); a pop-up says what stopped.
     * Phone and LAN work goes on.
     */
    private fun wifiLost() {
        if (mobileOk || !cellular.value) return
        val p = App.instance.player
        val s = p.state.value
        val list = ArrayList<String>()
        val item = s.item
        if (item != null && s.isPlaying && (item.isRadio || needsInternet(item.uri))) {
            stoppedPlayback = true; p.pause()
            list += (if (item.isRadio) "Radio: " else "Playing: ") + item.title
        }
        val rg = app.vlcinterface.sources.RgScanner
        if (rg.state.value.running && rg.currentNeedsInternet()) { list += "ReplayGain scan (queue kept)" }
        if (app.vlcinterface.sources.NetIndex.internetBusy()) list += "Indexing of internet folders"
        if (rg.state.value.running && rg.currentNeedsInternet() || app.vlcinterface.sources.NetIndex.internetBusy()) {
            if (!BackgroundWork.paused.value) { BackgroundWork.pause(); pausedWork = true }
        }
        // Radio recording (for saving songs) stops by itself on mobile data.
        // ("Always" in Settings: the recording goes on over mobile data — your choice.)
        if (item?.isRadio == true && App.instance.store.current.settings.radioCapture == "wifi") list += "Radio recording for saving songs"
        if (list.isEmpty()) return
        list += "Picture and song-link downloads"
        app.vlcinterface.data.ServerLog.log("network", "wifi-lost", "", "", false, "Wi-Fi lost: stopped what needs internet", list.joinToString(" · "))
        stopped.value = list
        ask.value = true
    }

    /** Wi-Fi back: work paused because of it goes on (playback stays stopped until you press play). */
    private fun wifiBack() {
        if (pausedWork) { pausedWork = false; BackgroundWork.resume() }
        stoppedPlayback = false
        if (ask.value) ask.value = false
    }

    /** "Continue on mobile data": everything goes on over mobile data until Wi-Fi is back. */
    fun goOnMobile() {
        mobileOk = true; ask.value = false
        if (stoppedPlayback) { stoppedPlayback = false; App.instance.player.replayCurrent() }
        if (pausedWork) { pausedWork = false; BackgroundWork.resume() }
    }

    /** "Stay stopped": the pop-up closes, nothing over mobile data. */
    fun stayStopped() { ask.value = false }

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
