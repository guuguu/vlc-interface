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
    /**
     * Mobile data allowed for this session ("Use NAS" on the player, or "Continue" / "Accept"): everything goes on
     * over it, no more precautions. Ends only after the player is unused for an hour (then asked again).
     */
    val mobileAllowed = MutableStateFlow(false)
    var mobileOk: Boolean get() = mobileAllowed.value; set(v) { mobileAllowed.value = v }
    /** Show the "use mobile data again?" question (player unused for an hour). */
    val idleAsk = MutableStateFlow(false)
    private const val IDLE_MS = 60 * 60_000L
    @Volatile private var lastUsed = System.currentTimeMillis()
    private var wifi = false
    private var online = false
    private var startBytes = -1L
    private var startKey = ""

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun read(@Suppress("UNUSED_PARAMETER") ignored: NetworkCapabilities?) {
            // The phone's real connections, under any VPN (ZeroTier, Tailscale … become the default network and hide
            // whether Wi-Fi or mobile data carries them).
            @Suppress("DEPRECATION")
            val real = try { cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } } catch (_: Exception) { emptyList() }
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val w = real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            val c = !w && real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            val wasWifi = wifi
            val on = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true || real.isNotEmpty()
            val wasOn = online
            // Wi-Fi → mobile data, or the connection dropped: the radio recording is held — kept if the connection is
            // back within the radio buffer and nothing is missing, else deleted. Wi-Fi back: decided / started again.
            if ((wasWifi && !w) || (wasOn && !on)) try { app.vlcinterface.sources.RadioCapture.netLost() } catch (_: Exception) {}
            if (w && on && (!wasWifi || !wasOn)) try { app.vlcinterface.sources.RadioCapture.netBack() } catch (_: Exception) {}
            online = on
            wifi = w; cellular.value = c
            if (wasWifi && !w) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ wifiLost() }, 800)
            if (!wasWifi && w) android.os.Handler(android.os.Looper.getMainLooper()).post { wifiBack() }
        }
        read(cm.getNetworkCapabilities(cm.activeNetwork))
        watch()
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(caps)
                override fun onLost(network: Network) = read(cm.getNetworkCapabilities(cm.activeNetwork))
            })
            // Wi-Fi / mobile data changing under a VPN doesn't change the default network: watched on their own too.
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = read(null)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = read(null)
                override fun onLost(network: Network) = read(null)
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
        // No Wi-Fi: anything not on the phone goes over mobile data (home servers only through the VPN).
        if (cellular.value) return true
        if (Regex("^(https?|ftps?|dav|davs|mms|rtsp|rtmp)://").containsMatchIn(u)) return true
        val host = android.net.Uri.parse(uri).host ?: return false
        // A home server reached through the VPN counts as internet only on mobile data (on any Wi-Fi it doesn't).
        if (cellular.value && app.vlcinterface.sources.VpnLink.routeHost(host) != host) return true
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
        mobileOk = true; ask.value = false; lastUsed = System.currentTimeMillis()
        if (stoppedPlayback) { stoppedPlayback = false; App.instance.player.replayCurrent() }
        if (pausedWork) { pausedWork = false; BackgroundWork.resume() }
    }

    /**
     * "Use NAS" on the player: you know mobile data is used and you're fine with it — for this session everything
     * goes on (network songs, NAS through the VPN, radio, background work, pictures and links), no more questions.
     */
    fun useNas(ctx: Context) {
        lastUsed = System.currentTimeMillis()
        val p = App.instance.player
        val wasBlocked = p.state.value.error.startsWith(BLOCKED)
        goOnMobile(); idleAsk.value = false
        val v = app.vlcinterface.sources.VpnLink
        v.clearSkip()
        if (v.useNas.value.isEmpty()) v.setUse(v.lastUsed.ifEmpty { v.lanServers().map { it.id }.toSet() })
        // Home servers are reached through the VPN: open it if it's off (the subnet check asks again if still needed).
        if (!v.vpnUp.value && App.instance.store.current.sources.any { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" }) {
            android.widget.Toast.makeText(ctx, v.startVpn(ctx), android.widget.Toast.LENGTH_LONG).show(); v.retryLater()
        }
        if (wasBlocked) p.replayCurrent()
        app.vlcinterface.data.ServerLog.log("network", "use-nas", "", "", true, "Mobile data allowed for this session (Use NAS)")
    }

    /** "Use NAS" with the chosen servers (from the button's list); empty = stop using the NAS. */
    fun useNas(ctx: Context, ids: Set<String>) {
        val v = app.vlcinterface.sources.VpnLink
        if (ids.isEmpty()) { v.setUse(emptySet()); mobileOk = false; return }
        v.setUse(ids); useNas(ctx)
    }

    /** Error shown on a song that waits for "Use NAS". */
    const val BLOCKED = "Waiting for “Use NAS”:"

    /** Answer to the hour-idle question. Accept: as "Use NAS". Deny: only phone files; what needs internet stops. */
    fun idleAnswer(ctx: Context, accept: Boolean) {
        idleAsk.value = false
        if (accept) { useNas(ctx); return }
        mobileOk = false; lastUsed = System.currentTimeMillis(); app.vlcinterface.sources.VpnLink.setUse(emptySet())
        stopInternet()
    }

    /** Without Wi-Fi and not allowed: playback that needs internet pauses, network background work waits. */
    private fun stopInternet() {
        val p = App.instance.player
        val s = p.state.value
        val item = s.item
        if (item != null && s.isPlaying && (item.isRadio || needsInternet(item.uri))) { stoppedPlayback = true; p.pause() }
        val rg = app.vlcinterface.sources.RgScanner
        if ((rg.state.value.running && rg.currentNeedsInternet() || app.vlcinterface.sources.NetIndex.internetBusy()) && !BackgroundWork.paused.value) {
            BackgroundWork.pause(); pausedWork = true
        }
    }

    /**
     * Every 30 s: the player in use keeps the session; unused (not playing) for an hour, mobile data has to be
     * allowed again (asked only when on mobile data). Without Wi-Fi and not allowed, network background work waits.
     */
    private fun watch() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val now = System.currentTimeMillis()
                    if (App.instance.player.state.value.isPlaying) lastUsed = now
                    if (mobileOk && now - lastUsed >= IDLE_MS) {
                        mobileOk = false; app.vlcinterface.sources.VpnLink.useNas.value = emptySet()
                        app.vlcinterface.data.ServerLog.log("network", "idle", "", "", true, "Player unused for an hour: mobile data has to be allowed again")
                        if (cellular.value) idleAsk.value = true
                    }
                    if (blocked()) stopInternet()
                } catch (_: Exception) {}
                h.postDelayed(this, 30_000)
            }
        }, 5_000)
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
