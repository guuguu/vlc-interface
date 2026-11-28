package app.vlcinterface.sources

import app.vlcinterface.data.CrashLog

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Home NAS over a VPN (ZeroTier, WireGuard, OpenVPN … any virtual interface / tunnel):
 *  - when a VPN comes up, its network is searched for SMB servers; each is identified (SMB server GUID, NetBIOS
 *    name, MAC) and compared with the LAN servers set in the app; a likely match is asked about ("Is 10.147.17.5
 *    the same as Transcend - 192.168.5.1?") and, when confirmed, kept as that server's VPN address;
 *  - from then on the server is reached at its VPN address while the VPN is on and the phone is not on the
 *    server's own network, and at its LAN address again as soon as the Wi-Fi is on that network (every switch said);
 *  - away from home with the VPN off, the app asks whether to start the VPN (it opens the VPN app: Android doesn't
 *    let one app switch another app's VPN on by itself — said so).
 */
object VpnLink {
    data class Found(val ip: String, val guid: String, val nbName: String, val mac: String)

    /** A VPN / tunnel is up. */
    val vpnUp = MutableStateFlow(false)
    /** "Is <found> the same as <source>?" */
    val ask = MutableStateFlow<Pair<Source, Found>?>(null)
    /** Away from home, VPN off: "Start the VPN?" */
    val askStart = MutableStateFlow<Source?>(null)
    /** Searching the VPN now (Settings shows it). */
    val searching = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)
    private lateinit var appCtx: Context
    private class Net(val addr: Int, val prefix: Int) { fun contains(ip: Int) = prefix == 0 || ((ip xor addr) ushr (32 - prefix)) == 0 }
    @Volatile private var homeNets: List<Net> = emptyList()      // non-VPN networks (Wi-Fi / Ethernet)
    @Volatile private var vpnNets: List<Net> = emptyList()       // VPN networks (its own addresses and routes)
    @Volatile private var vpnSelf: Set<Int> = emptySet()
    private val usingVpn = HashMap<String, Boolean>()            // source id → reached through the VPN now
    private var askedStartFor = ""
    private val declined = HashSet<String>()                     // "<source id>|<ip>" answered No

    private fun ipInt(s: String): Int? = try {
        val a = InetAddress.getByName(s) as? Inet4Address ?: return null
        val b = a.address; ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    } catch (_: Exception) { null }
    private fun ipStr(i: Int) = "${i ushr 24 and 0xFF}.${i ushr 16 and 0xFF}.${i ushr 8 and 0xFF}.${i and 0xFF}"
    private fun isIpLiteral(h: String) = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        // Every network, VPNs included.
        val req = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        try {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh(cm)
                override fun onLost(network: Network) = refresh(cm)
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh(cm)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh(cm)
            })
        } catch (_: Exception) {}
        refresh(cm)
    }

    @Volatile private var refreshing = false
    private fun refresh(cm: ConnectivityManager) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            delay(1500)          // let the change settle (several callbacks come at once)
            refreshing = false
            val home = ArrayList<Net>(); val vpn = ArrayList<Net>(); val self = HashSet<Int>()
            val seen = ArrayList<String>()                       // for the VPN log: interface, kind, addresses, routes
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n) ?: continue
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val kind = when { isVpn -> "VPN"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"; caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"; else -> "other" }
                seen += "$kind (${lp.interfaceName ?: "?"}): " + lp.linkAddresses.joinToString { "${it.address.hostAddress}/${it.prefixLength}" } +
                    if (isVpn) " · routes " + lp.routes.mapNotNull { rt -> rt.destination.takeIf { it.prefixLength in 1..32 }?.toString() }.distinct().joinToString() else ""
                for (la in lp.linkAddresses) {
                    val a = la.address as? Inet4Address ?: continue
                    val ip = ipInt(a.hostAddress ?: continue) ?: continue
                    (if (isVpn) vpn else home) += Net(ip, la.prefixLength); if (isVpn) self += ip
                }
                if (isVpn) for (r in lp.routes) {
                    val d = r.destination; val a = d.address as? Inet4Address ?: continue
                    if (d.prefixLength in 8..32) ipInt(a.hostAddress ?: continue)?.let { vpn += Net(it, d.prefixLength) }
                }
            }
            // Any virtual interface / tunnel (zt*, tun*, wg*, ppp*, ipsec*) counts too, also without a VPN network.
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || !Regex("^(zt|tun|wg|ppp|ipsec|tap)", RegexOption.IGNORE_CASE).containsMatchIn(ni.name)) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address as? Inet4Address ?: continue
                        val ip = ipInt(a.hostAddress ?: continue) ?: continue
                        if (ip !in self) { vpn += Net(ip, ia.networkPrefixLength.toInt()); self += ip; seen += "tunnel ${ni.name}: ${a.hostAddress}/${ia.networkPrefixLength}" }
                    }
                }
            } catch (_: Exception) {}
            val wasUp = vpnUp.value
            val netKey = (home + vpn).joinToString { "${it.addr}/${it.prefix}" }
            val netChanged = netKey != lastNetKey; lastNetKey = netKey
            // VPN log: the phone's own addresses (Wi-Fi / mobile / VPN) each time they change.
            if (netChanged) ServerLog.log("vpn", "phone", "", "", true,
                "Phone addresses: " + (if (self.isEmpty()) "no VPN" else "VPN ${self.joinToString { ipStr(it) }}"), seen.joinToString("\n"))
            homeNets = home; vpnNets = vpn; vpnSelf = self
            vpnUp.value = vpn.isNotEmpty()
            // Same storage, other road: the index (kept by server, with LAN addresses) is used as it is — only who
            // answers now is checked again; nothing is indexed anew.
            NetIndex.recheckReachable()
            learnLocal()
            announceSwitches()
            if (vpnUp.value && !wasUp) {
                ServerLog.log("vpn", "up", "vpn", "", true, "VPN / tunnel up: " + self.joinToString { ipStr(it) })
                NetIndex.vpnOn()
                // The VPN network in use is searched at once: home servers get their address there, new ones are offered.
                if (on() || autoFor.isNotEmpty()) rescan()
                if (useNas.value.isNotEmpty()) try { App.instance.player.restoreNas() } catch (_: Exception) {}
            }
            // Still on the VPN but another network under it (Wi-Fi ↔ mobile, another VPN network): searched again.
            if (vpnUp.value && wasUp && netChanged && on()) rescan()
            if (!vpnUp.value && wasUp) { ServerLog.log("vpn", "down", "vpn", "", true, "VPN / tunnel down"); NetIndex.vpnOff() }
            checkNeedVpn()
        }
    }

    /** On the server's own network (the Wi-Fi / Ethernet holds its LAN address). */
    fun onLan(host: String): Boolean { val ip = ipInt(host) ?: return true; return homeNets.any { it.contains(ip) } }

    /** Reached through its VPN address now: VPN up, a VPN address known, not on its own network. */
    private fun on() = try { App.instance.store.current.settings.vpnNas } catch (_: Exception) { true }

    /**
     * The address to use for [s] now: chosen by hand (Libraries → LAN: its LAN address or any stored one), or Auto —
     * its VPN address while the VPN is on and the phone isn't on its own network, else its LAN address.
     */
    fun hostNow(s: Source): String = when (s.addrMode) {
        "lan" -> s.host
        "vpn" -> s.vpnHost.ifBlank { s.host }
        "auto", "" -> autoHost(s)
        else -> if (s.addrMode in s.altHosts) s.addrMode else s.host
    }

    /**
     * Auto: the LAN address at home; another stored address on the network the phone is on now; away with the
     * VPN up, any stored address on the VPN's network (found by the search or added by hand — not only the one
     * kept as "VPN address"), else the noted VPN address; otherwise the LAN address.
     */
    private fun autoHost(s: Source): String {
        if (onLan(s.host)) return s.host
        s.altHosts.firstOrNull { isIpLiteral(it) && onLan(it) }?.let { return it }
        if (on() && vpnUp.value) {
            if (s.vpnHost.isNotBlank()) return s.vpnHost                    // the one the last search found
            vpnAddresses(s).firstOrNull()?.let { return it }
        }
        return s.host
    }


    private fun sourceOf(host: String): Source? = try {
        App.instance.store.current.sources.firstOrNull { it.host.equals(host, true) && (it.vpnHost.isNotBlank() || it.altHosts.isNotEmpty()) }
    } catch (_: Exception) { null }

    /** The host to connect to now for [host]. */
    fun routeHost(host: String): String = sourceOf(host)?.let { hostNow(it) } ?: host

    /** Libraries → LAN: the address chosen by hand ("auto", "lan" or one of the stored addresses). */
    fun setMode(s: Source, mode: String) {
        App.instance.store.update { x -> x.copy(sources = x.sources.map { if (it.id == s.id) it.copy(addrMode = mode) else it }) }
        scope.launch { delay(300); announceSwitches() }
        NetIndex.recheckReachable()
    }

    /** Another address of the same server, stored (Libraries → LAN → +). */
    fun addAddress(s: Source, ip: String) {
        val a = ip.trim(); if (a.isBlank() || a.equals(s.host, true)) return
        App.instance.store.update { x -> x.copy(sources = x.sources.map {
            if (it.id == s.id) it.copy(altHosts = (it.altHosts + a).distinct(), vpnHost = it.vpnHost.ifBlank { a }) else it }) }
        ServerLog.log("vpn", "address", "smb", "", true, "${s.name.ifBlank { s.host }}: address $a stored")
    }

    fun removeAddress(s: Source, ip: String) {
        App.instance.store.update { x -> x.copy(sources = x.sources.map {
            if (it.id != s.id) it else {
                val left = it.altHosts - ip
                it.copy(altHosts = left, vpnHost = if (it.vpnHost == ip) left.firstOrNull() ?: "" else it.vpnHost,
                    addrMode = if (it.addrMode == ip || (it.addrMode == "vpn" && left.isEmpty())) "auto" else it.addrMode)
            } }) }
        ServerLog.log("vpn", "address", "smb", "", true, "${s.name.ifBlank { s.host }}: address $ip removed")
    }

    /** The address in use for [url] now: a LAN server's address swapped for the one chosen / its VPN one. */
    fun route(url: String): String {
        val m = Regex("^(\\w+://(?:[^/@]*@)?)([^/:]+)(.*)$").find(url) ?: return url
        val src = sourceOf(m.groupValues[2]) ?: return url
        val h = hostNow(src)
        return if (h.equals(src.host, true)) url else m.groupValues[1] + h + m.groupValues[3]
    }

    /** Back to the server's LAN address (what the app keeps in lists and playlists). */
    fun unroute(url: String): String {
        val m = Regex("^(\\w+://(?:[^/@]*@)?)([^/:]+)(.*)$").find(url) ?: return url
        val host = m.groupValues[2]
        val src = try { App.instance.store.current.sources.firstOrNull { s -> s.vpnHost.equals(host, true) || s.altHosts.any { it.equals(host, true) } } } catch (_: Exception) { null } ?: return url
        return m.groupValues[1] + src.host + m.groupValues[3]
    }

    /** Every switch LAN ↔ VPN is said (pop-up and the VPN log). */
    private fun announceSwitches() {
        val srcs = App.instance.store.current.sources.filter { it.vpnHost.isNotBlank() || it.altHosts.isNotEmpty() }
        for (s in srcs) {
            val h = hostNow(s)
            val now = h != s.host
            val before = usingVpn[s.id]
            usingVpn[s.id] = now
            if (before == null || before == now) continue
            val msg = if (now) "${s.name.ifBlank { s.host }}: using $h" + (if (s.addrMode == "auto") " — not on its home network" else " (chosen)")
                else "${s.name.ifBlank { s.host }}: back on the LAN address (${s.host})"
            // Transparent: no pop-up — written in the VPN log; Libraries → LAN shows the address in use.
            ServerLog.log("vpn", "switch", "vpn", "", true, msg)
        }
    }

    @Volatile private var lastNetKey = ""
    /** Wait per address when searching for NAS on the VPN (ZeroTier sets up a path on first contact). */
    private const val SCAN_MS = 2000
    /** This phone's own addresses on the VPN (e.g. its ZeroTier IP) and the VPN networks, for Settings. */
    fun phoneVpnInfo(): String {
        val mine = vpnSelf.map { ipStr(it) }.sorted()
        val nets = vpnNets.map { n -> val p = if (n.prefix >= 22) n.prefix else 24; "${ipStr(n.addr and (if (p == 0) 0 else (-1 shl (32 - p))))}/$p" }.distinct()
        return if (mine.isEmpty()) "" else "This phone on the VPN: ${mine.joinToString()} · searched for NAS: ${nets.joinToString()}"
    }

    /** Short note (3 s) of the network being searched for NAS. */
    val scanNote = MutableStateFlow("")

    // ---------- file servers on the VPN not set up yet ----------
    /** A file server found on the VPN that matches no NAS: offered as a new one (one at a time). */
    val askNew = MutableStateFlow<Found?>(null)
    private val newQueue = java.util.ArrayDeque<Found>()
    private fun ignoredFile() = File(App.instance.filesDir, "vpn-ignored-servers.txt")
    private fun idOf(f: Found) = f.guid.ifEmpty { f.nbName.ifEmpty { f.ip } }
    private fun ignored(): Set<String> = try { ignoredFile().readLines().toSet() } catch (_: Exception) { emptySet() }

    @Synchronized private fun offerNew(list: List<Found>) {
        val skip = ignored()
        list.filter { idOf(it) !in skip && newQueue.none { q -> idOf(q) == idOf(it) } && askNew.value?.let { a -> idOf(a) == idOf(it) } != true }
            .forEach { newQueue.add(it) }
        if (askNew.value == null) askNew.value = newQueue.pollFirst()
    }

    /** "Set up": a new NAS from the server found (its VPN address; name and identity as it gave them). */
    @Synchronized fun addNew(f: Found) {
        App.instance.store.update { x -> x.copy(sources = x.sources + Source(zone = app.vlcinterface.data.Zone.LAN, proto = "smb",
            name = f.nbName.ifEmpty { f.ip }, host = f.ip, vpnHost = f.ip, altHosts = listOf(f.ip),
            smbGuid = f.guid, nbName = f.nbName, mac = f.mac)) }
        ServerLog.log("vpn", "new", "smb", "", true, "New NAS set up from the VPN: ${f.nbName.ifEmpty { f.ip }} at ${f.ip}")
        askNew.value = newQueue.pollFirst()
    }

    /** "Not now" (asked again at the next search) or "Never" (this server isn't offered again). */
    @Synchronized fun skipNew(f: Found, never: Boolean) {
        if (never) try { ignoredFile().appendText(idOf(f) + "\n") } catch (_: Exception) {}
        askNew.value = newQueue.pollFirst()
    }

    /** "Not this session": network songs (LAN servers) are skipped until the phone is on their network again. */
    @Volatile var ignoreSession = false; private set
    @Volatile private var retryAt = 0L

    /** On a network of [addr]: one of the phone's addresses (Wi-Fi, VPN …) is on the same subnet. */
    private fun onSubnetOf(addr: String): Boolean { val ip = ipInt(addr) ?: return true; return (homeNets + vpnNets).any { it.contains(ip) } }

    /** The phone is on a subnet of [s]'s LAN address or of any stored one. */
    fun nearby(s: Source): Boolean = onSubnetOf(s.host) || s.altHosts.any { onSubnetOf(it) } || (s.vpnHost.isNotBlank() && onSubnetOf(s.vpnHost))

    /** NAS / shares (Libraries → LAN) in use away from home this session ("Use NAS"); empty = none. */
    val useNas = MutableStateFlow<Set<String>>(emptySet())
    /** Ask "use the NAS away from home?" (once per session / network change). */
    val askUse = MutableStateFlow(false)
    @Volatile private var askedUseFor = ""
    @Volatile var lastUsed: Set<String> = emptySet(); private set

    /** The LAN servers and shares stored on Libraries → LAN. */
    fun lanServers(): List<Source> = App.instance.store.current.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" }

    /** On [s]'s home subnet (its LAN address or a stored non-VPN one), i.e. reached directly. */
    fun atHome(s: Source): Boolean = onLan(s.host) || s.altHosts.any { it != s.vpnHost && isIpLiteral(it) && onLan(it) }

    /** The LAN server a song address belongs to (LAN address or any stored one). */
    fun serverOfUri(uri: String): Source? {
        if (!uri.startsWith("smb://")) return null
        val host = android.net.Uri.parse(uri).host ?: return null
        return lanServers().firstOrNull { it.host.equals(host, true) || it.altHosts.any { a -> a.equals(host, true) } || it.vpnHost.equals(host, true) }
    }

    /** Away from home and this NAS not chosen with "Use NAS": its songs wait for the button. */
    fun waitsForUse(uri: String): Boolean { val s = serverOfUri(uri) ?: return false; return !atHome(s) && (s.id !in useNas.value || !vpnUp.value) }   // away: only chosen, and only with the VPN on

    /**
     * VPN-NAS switched on by hand: are you on Wi-Fi or mobile data? Wi-Fi unlocks the heavy work on the NAS away
     * from home (caching ahead, ReplayGain scans and updates); mobile data: playback only. null = not answered yet.
     */
    @Volatile var nasOnWifi: Boolean? = null
    val askLink = MutableStateFlow(false)

    /** Heavy transfer (caching ahead, ReplayGain) on a NAS away from home, not unlocked with "Wi-Fi". */
    fun heavyBlocked(uri: String): Boolean { val s = serverOfUri(uri) ?: return false; return !atHome(s) && nasOnWifi != true }

    fun answerLink(wifi: Boolean) {
        askLink.value = false; nasOnWifi = wifi
        ServerLog.log("vpn", "link", "smb", "", true, if (wifi) "VPN-NAS on Wi-Fi: caching ahead and ReplayGain allowed" else "VPN-NAS on mobile data: playback only")
        if (wifi) Prefetch.kick()
    }

    /** "Use NAS" with these servers (empty: stop using them). Also allows mobile data for the session. */
    fun setUse(ids: Set<String>) {
        useNas.value = ids; askUse.value = false
        if (ids.isEmpty()) { nasOnWifi = null; askLink.value = false }
        try { App.instance.player.pruneNas() } catch (_: Exception) {}
        if (ids.isNotEmpty()) { lastUsed = ids; ignoreSession = false
            if (vpnUp.value && lanServers().any { it.id in ids && !atHome(it) }) { autoFor = autoFor + ids; rescan() } else autoFind(ids)
            if (vpnUp.value) try { App.instance.player.restoreNas() } catch (_: Exception) {} }
        ServerLog.log("vpn", "use-nas", "smb", "", true, if (ids.isEmpty()) "NAS not used (away from home)"
            else "NAS used this session: " + lanServers().filter { it.id in ids }.joinToString { it.name.ifBlank { it.host } })
    }

    /** Reachable now at the address in use (port 445). Blocking: call off the main thread. */
    fun reachableNow(s: Source): Boolean = reachable(hostNow(s), 445, 1500)

    /** The server's addresses on the VPN's network (stored ones). */
    fun vpnAddresses(s: Source): List<String> = (listOf(s.vpnHost) + s.altHosts)
        .filter { isIpLiteral(it) && ipInt(it)?.let { ip -> vpnNets.any { n -> n.contains(ip) } } == true }.distinct()

    /**
     * Away from home: what the VPN side looks like for [s], in plain words — reachable (and at which address),
     * no VPN address known yet, or the address answers but its file sharing (SMB, 445) doesn't listen there.
     * Returns (ok, text).
     */
    fun vpnDiagnosis(s: Source): Pair<Boolean, String> {
        if (!vpnUp.value) return false to "VPN off"
        val addrs = vpnAddresses(s)
        if (addrs.isEmpty()) return false to "no VPN address known for it yet"
        addrs.firstOrNull { reachable(it, 445, 1500) }?.let { return true to "$it · reachable over the VPN" }
        val a = addrs.first()
        return false to if (reachable(a, 22, 1200) || reachable(a, 80, 1200) || reachable(a, 443, 1200))
            "$a answers, but its file sharing (SMB, port 445) doesn't listen on the VPN side"
        else "$a · not reachable over the VPN"
    }

    /** A network song to skip ("Not this session"). */
    fun ignored(uri: String): Boolean {
        if (!ignoreSession || !uri.startsWith("smb://")) return false
        val host = android.net.Uri.parse(uri).host ?: return false
        return App.instance.store.current.sources.any { it.zone == app.vlcinterface.data.Zone.LAN && it.host.equals(host, true) }
    }

    /**
     * The phone's address isn't on the subnet of any address stored for a LAN server (home Wi-Fi, VPN …): starting
     * the VPN is suggested — Start the VPN / Retry in N s (time to start it; Settings → VPN) / Not this session.
     */
    private fun checkNeedVpn() {
        try { App.instance.player.pruneNas() } catch (_: Exception) {}      // NAS not usable: its songs leave the queue
        val st = App.instance.store.current
        val servers = st.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" && isIpLiteral(it.host) }
        val away = servers.filter { !nearby(it) }
        // Away from the home subnet with the VPN on: ask once whether the NAS should be used (the player's
        // "Use NAS" button does it any time later).
        val notHome = servers.filter { !atHome(it) }
        if (notHome.isEmpty()) { askedUseFor = ""; if (useNas.value.isNotEmpty()) useNas.value = emptySet() }
        else if (vpnUp.value && useNas.value.isEmpty() && !ignoreSession) {
            val k = (homeNets + vpnNets).joinToString { "${it.addr}/${it.prefix}" }
            if (k != askedUseFor) { askedUseFor = k; askUse.value = true }
        }
        if (away.isEmpty()) { askedStartFor = ""; ignoreSession = false; return }        // back near a server: all as usual
        if (!on() || !st.settings.vpnAskStart || ignoreSession) return
        if (app.vlcinterface.player.NetGuard.blocked()) return          // no Wi-Fi: nothing until "Use NAS"
        if (System.currentTimeMillis() < retryAt) return
        val s = away.first()
        val key = s.id + (homeNets + vpnNets).joinToString { "${it.addr}/${it.prefix}" }
        if (key == askedStartFor) return
        // Really not reachable (a route may still lead to it).
        if (reachable(hostNow(s), 445, 800)) return
        askedStartFor = key
        askStart.value = s
    }

    /** "Retry in N s": the question comes back after that if the server is still out of reach. */
    fun retryLater() {
        askStart.value = null
        val sec = App.instance.store.current.settings.vpnRetrySec
        retryAt = System.currentTimeMillis() + sec * 1000L
        askedStartFor = ""
        scope.launch { delay(sec * 1000L + 200); NetIndex.recheckReachable(); checkNeedVpn() }
    }

    /** "Use NAS": network songs back on. */
    fun clearSkip() { ignoreSession = false; askedStartFor = ""; retryAt = 0 }

    /** "Not this session": network songs skipped until the phone is near a server again. */
    fun skipSession() {
        askStart.value = null; ignoreSession = true
        try { App.instance.player.pruneNas() } catch (_: Exception) {}
        ServerLog.log("vpn", "session", "smb", "", true, "Network songs skipped for this session (no VPN)")
    }

    /** Opens the VPN app (ZeroTier when installed, else Android's VPN settings) — it can't be switched on from here. */
    fun startVpn(ctx: Context): String {
        autoFind(useNas.value.ifEmpty { askStart.value?.let { setOf(it.id) } ?: emptySet() })
        val pm = ctx.packageManager
        // The VPN app chosen in Settings → VPN (its own login is used — nothing to enter here), else the first installed.
        val apps = when (App.instance.store.current.settings.vpnApp) {
            "zerotier" -> listOf("com.zerotier.one")
            "tailscale" -> listOf("com.tailscale.ipn")
            "settings" -> emptyList()
            else -> listOf("com.zerotier.one", "com.tailscale.ipn", "com.wireguard.android", "net.openvpn.openvpn", "de.blinkt.openvpn")
        }
        for (p in apps) pm.getLaunchIntentForPackage(p)?.let {
            ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened ${pm.getApplicationLabel(pm.getApplicationInfo(p, 0))}: switch the VPN on there — Android doesn't let this app do it itself"
        }
        try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        return "Opened Android's VPN settings: switch the VPN on there — Android doesn't let this app do it itself"
    }

    // ---------------------------------------------------------------- finding and identifying servers

    private fun reachable(ip: String, port: Int, ms: Int): Boolean = try { Socket().use { it.connect(InetSocketAddress(ip, port), ms) }; true } catch (_: Exception) { false }

    /** SMB2 NEGOTIATE (TCP 445): the server's GUID (unique per server). */
    private fun smbGuid(ip: String): String {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 445), 1500); s.soTimeout = 2000
                val body = java.nio.ByteBuffer.allocate(64 + 40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                body.put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
                body.putShort(64); body.putShort(0); body.putInt(0); body.putShort(0); body.putShort(1)
                body.putInt(0); body.putInt(0); body.putLong(0); body.putInt(0); body.putInt(0); body.putLong(0); body.put(ByteArray(16))
                body.putShort(36); body.putShort(2); body.putShort(1); body.putShort(0); body.putInt(0)
                body.put(ByteArray(16) { (it * 7 + 3).toByte() }); body.putLong(0)
                body.putShort(0x0202); body.putShort(0x0210)
                val msg = body.array()
                val out = s.getOutputStream()
                out.write(byteArrayOf(0, 0, (msg.size shr 8).toByte(), msg.size.toByte())); out.write(msg); out.flush()
                val inp = java.io.DataInputStream(s.getInputStream())
                val hdr = ByteArray(4); inp.readFully(hdr)
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                val r = ByteArray(len); inp.readFully(r)
                if (len < 64 + 24 || r[0] != 0xFE.toByte()) return ""
                return (64 + 8 until 64 + 24).joinToString("") { "%02x".format(r[it]) }
            }
        } catch (_: Exception) { return "" }
    }


    /** Server GUID and host name in one TCP connection (NEGOTIATE answer, then the NTLM challenge). */
    fun smbIdentity(ip: String): Pair<String, String> {
        var guid = ""
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 445), 1500); s.soTimeout = 2500
                val out = s.getOutputStream(); val inp = java.io.DataInputStream(s.getInputStream())
                fun send(msg: ByteArray) { out.write(byteArrayOf(0, 0, (msg.size shr 8).toByte(), msg.size.toByte())); out.write(msg); out.flush() }
                fun recv(): ByteArray {
                    val h = ByteArray(4); inp.readFully(h)
                    val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                    return ByteArray(len).also { inp.readFully(it) }
                }
                fun header(b: java.nio.ByteBuffer, command: Int, messageId: Long) {
                    b.put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
                    b.putShort(64); b.putShort(1)                    // header size, credit charge
                    b.putInt(0)                                      // status
                    b.putShort(command.toShort()); b.putShort(1)     // command, credits asked
                    b.putInt(0); b.putInt(0)                         // flags, next command
                    b.putLong(messageId); b.putInt(0); b.putInt(0)   // message id, reserved, tree id
                    b.putLong(0); b.put(ByteArray(16))               // session id, signature
                }
                // 1. NEGOTIATE (SMB 2.0.2 / 2.1 / 3.0 / 3.0.2)
                val neg = java.nio.ByteBuffer.allocate(64 + 36 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header(neg, 0, 0)
                neg.putShort(36); neg.putShort(4); neg.putShort(1); neg.putShort(0); neg.putInt(0)
                neg.put(ByteArray(16) { (it * 7 + 3).toByte() }); neg.putLong(0)
                neg.putShort(0x0202); neg.putShort(0x0210); neg.putShort(0x0300); neg.putShort(0x0302)
                send(neg.array())
                val nr = recv(); if (nr.size < 64 || nr[0] != 0xFE.toByte()) return "" to ""
                if (nr.size >= 64 + 24) guid = (64 + 8 until 64 + 24).joinToString("") { "%02x".format(nr[it]) }
                // 2. SESSION_SETUP with NTLMSSP NEGOTIATE (type 1)
                val ntlm = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                ntlm.put("NTLMSSP".toByteArray()); ntlm.put(0); ntlm.putInt(1)
                ntlm.putInt(0xE2088297.toInt())                       // unicode, OEM, request target, NTLM, ext. security …
                ntlm.put(ByteArray(16))                              // domain / workstation fields: empty
                val blob = ntlm.array()
                val ss = java.nio.ByteBuffer.allocate(64 + 24 + blob.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header(ss, 1, 1)
                ss.putShort(25); ss.put(0); ss.put(1); ss.putInt(0); ss.putInt(0)
                ss.putShort((64 + 24).toShort()); ss.putShort(blob.size.toShort()); ss.putLong(0)
                ss.put(blob)
                send(ss.array())
                val r = recv(); if (r.size < 64 + 8) return guid to ""
                return guid to ntlmTargetName(r)
            }
        } catch (_: Exception) { return guid to "" }
    }

    /** NetBIOS computer name (else DNS host name) from the NTLM challenge found in [r] (raw or in SPNEGO). */
    fun ntlmTargetName(r: ByteArray): String {
        val sig = "NTLMSSP".toByteArray() + 0
        var at = -1
        for (i in 0..r.size - sig.size) if ((sig.indices).all { r[i + it] == sig[it] }) { at = i; break }
        if (at < 0 || at + 48 > r.size) return ""
        fun u16(o: Int) = (r[o].toInt() and 0xFF) or ((r[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (u32(at + 8) != 2) return ""                          // a CHALLENGE message
        val tiLen = u16(at + 40); val tiOff = u32(at + 44)
        var p = at + tiOff; val end = minOf(r.size, p + tiLen)
        var nb = ""; var dns = ""
        while (p + 4 <= end) {
            val id = u16(p); val len = u16(p + 2); p += 4
            if (id == 0 || p + len > end) break
            val v = String(r, p, len, Charsets.UTF_16LE)
            if (id == 1) nb = v else if (id == 3) dns = v
            p += len
        }
        return nb.ifEmpty { dns.substringBefore('.') }.uppercase()
    }

    /**
     * Who a server is, over TCP only (port 445, the same as the file sharing — passes any VPN and firewall that
     * lets the shares through): server GUID and host name from one SMB connection. No UDP (NetBIOS 137) any more.
     */
    fun identify(ip: String): Found {
        val (guid, host) = smbIdentity(ip)
        return Found(ip, guid.ifEmpty { smbGuid(ip) }, host, "")
    }

    /** At home: the LAN servers' own identity is noted (to recognise them on the VPN later). */
    private fun learnLocal() {
        val st = App.instance.store
        for (s in st.current.sources.filter { it.proto == "smb" && isIpLiteral(it.host) && onLan(it.host) }) {
            if (s.smbGuid.isNotBlank() && s.nbName.isNotBlank()) continue
            if (!reachable(s.host, 445, 800)) continue
            val f = identify(s.host)
            if (f.guid.isEmpty() && f.nbName.isEmpty()) continue
            st.update { x -> x.copy(sources = x.sources.map { if (it.id == s.id) it.copy(smbGuid = f.guid.ifEmpty { it.smbGuid }, nbName = f.nbName.ifEmpty { it.nbName }, mac = f.mac.ifEmpty { it.mac }) else it }) }
            ServerLog.log("vpn", "learn", "smb", "", true, "${s.name.ifBlank { s.host }} identified on the LAN", "guid ${f.guid} · name ${f.nbName} · mac ${f.mac}")
        }
    }

    /** How alike a found server and a set one are: 3 = same GUID, 2 = same NetBIOS name / MAC, 1 = name like it, 0 = no. */
    private fun likeness(f: Found, s: Source): Int = when {
        f.guid.isNotEmpty() && f.guid == s.smbGuid -> 3
        f.mac.isNotEmpty() && f.mac == s.mac -> 2
        f.nbName.isNotEmpty() && f.nbName.equals(s.nbName, true) -> 2
        f.nbName.isNotEmpty() && (s.name.contains(f.nbName, true) || f.nbName.contains(s.name.ifBlank { "\u0000" }, true)) -> 1
        else -> 0
    }

    /** Searches the VPN's networks for SMB servers and asks about those like a LAN server (also from Settings). */
    /** Servers chosen with "Use NAS" that have no VPN address yet: found on the VPN and added without a question. */
    @Volatile private var autoFor: Set<String> = emptySet()

    /** A VPN address is stored for [s] (when the VPN is down: any VPN address noted). */
    private fun hasVpnAddr(s: Source): Boolean {
        val all = (listOf(s.vpnHost) + s.altHosts).filter { isIpLiteral(it) }
        if (vpnNets.isEmpty()) return s.vpnHost.isNotBlank()
        return all.any { a -> ipInt(a)?.let { ip -> vpnNets.any { it.contains(ip) } } == true }
    }

    /** After "Use NAS" (or Yes on its question): chosen servers without a VPN address are looked for on the VPN now,
     *  or as soon as it's up, and their address is added as another one (no search by hand in Settings). */
    private fun autoFind(ids: Set<String>) {
        val need = lanServers().filter { it.id in ids && !hasVpnAddr(it) }.map { it.id }.toSet()
        if (need.isEmpty()) return
        autoFor = autoFor + need
        if (vpnUp.value) discover()
    }

    /**
     * Fresh search of the VPN network for the home servers, whatever addresses are stored: every file server
     * found is identified (server ID, name, network card) and the matching NAS gets the address found now — so a
     * changed or wrong stored address never hides it. Used by the NAS picker, "Use", and Settings → Search.
     */
    fun rescan() = discover(fresh = true)

    /**
     * NAS not found on the VPN: which network to search is asked — offered: the VPN networks the phone has now
     * (and its other networks), or one typed. Kept and searched again next times (see [extraNets]).
     */
    val askSubnet = MutableStateFlow<List<String>>(emptyList())
    private fun extraFile() = File(App.instance.filesDir, "vpn-extra-subnets.txt")
    /** Networks added by hand: searched together with the VPN's own. */
    fun extraNets(): List<String> = try { extraFile().readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }

    /** "a.b.c.d/nn" → Net (prefix 20…32: at most 4096 addresses). */
    fun validSubnet(t: String) = parseNet(t) != null
    private fun parseNet(t: String): Net? {
        val m = Regex("""^\s*(\d{1,3}(?:\.\d{1,3}){3})\s*/\s*(\d{1,2})\s*$""").find(t) ?: return null
        val ip = ipInt(m.groupValues[1]) ?: return null
        val p = m.groupValues[2].toInt(); if (p !in 20..32) return null
        return Net(ip, p)
    }

    /** Search this network (typed or picked) now; kept for later searches. */
    fun searchSubnet(cidr: String): Boolean {
        val n = parseNet(cidr) ?: return false
        askSubnet.value = emptyList()
        val norm = "${ipStr(n.addr and (if (n.prefix == 0) 0 else (-1 shl (32 - n.prefix))))}/${n.prefix}"
        if (norm !in extraNets()) try { extraFile().appendText(norm + "\n") } catch (_: Exception) {}
        ServerLog.log("vpn", "subnet", "", "", true, "Network to search added by hand: $norm")
        discover(fresh = true, only = listOf(n))
        return true
    }

    /** What the phone has now, to offer in the question. */
    private fun subnetChoices(): List<String> {
        fun cidr(n: Net): String { val p = if (n.prefix >= 20) n.prefix else 24; return "${ipStr(n.addr and (if (p == 0) 0 else (-1 shl (32 - p))))}/$p" }
        return (vpnNets.map { cidr(it) } + extraNets() + homeNets.map { cidr(it) }).distinct()
    }

    private fun discover(fresh: Boolean = false, only: List<Net>? = null) {
        scope.launch {
            if (searching.value.isNotEmpty()) { if (autoFor.isNotEmpty() || fresh) { while (searching.value.isNotEmpty()) delay(500); discover(fresh, only) }; return@launch }
            val nets = only ?: (vpnNets + extraNets().mapNotNull { parseNet(it) })
            if (nets.isEmpty()) return@launch
            // The VPN's own /24s (and routed nets up to /22): at most ~1000 addresses.
            val targets = LinkedHashSet<Int>()
            for (n in nets) {
                val p = n.prefix.coerceAtLeast(if (n.prefix >= 22) n.prefix else 24)
                val base = n.addr and (if (p == 0) 0 else (-1 shl (32 - p)))
                val count = 1 shl (32 - p)
                if (count <= 2) targets += n.addr
                for (i in 1 until count - 1) { targets += base + i; if (targets.size > 1100) break }
            }
            targets.removeAll(vpnSelf)
            // Which network is searched: shown for 3 s.
            val nets2 = nets.map { n -> val p = if (n.prefix >= 22) n.prefix else 24; "${ipStr(n.addr and (if (p == 0) 0 else (-1 shl (32 - p))))}/$p" }.distinct()
            scanNote.value = "Searching for NAS on ${nets2.joinToString()} (${targets.size} addresses)"
            val scanStart = System.currentTimeMillis()
            ServerLog.log("vpn", "scan", "TCP 445", "", true, "Search for NAS started on ${nets2.joinToString()}: ${targets.size} addresses",
                "from this phone's VPN address ${vpnSelf.joinToString { ipStr(it) }} · " +
                (if (targets.isEmpty()) "" else "${ipStr(targets.min())} … ${ipStr(targets.max())}") + (if (fresh) " · fresh search" else ""))
            searching.value = "Searching for NAS… (${nets2.joinToString()})"
            // Over ZeroTier the first packet to a peer can take well over a second (the path is set up first):
            // 2 s per address, many at once.
            val sem = Semaphore(128)
            val open = targets.map { ip -> async { sem.withPermit { if (reachable(ipStr(ip), 445, SCAN_MS)) ipStr(ip) else null } } }.awaitAll().filterNotNull()
            ServerLog.log("vpn", "scan", "TCP 445", "", open.isNotEmpty(),
                "Scanned ${targets.size} addresses on ${nets2.joinToString()} in ${"%.1f".format(java.util.Locale.US, (System.currentTimeMillis() - scanStart) / 1000.0)} s: " +
                    (if (open.isEmpty()) "no file server (port 445) answered" else "${open.size} answered: ${open.joinToString()}"),
                "addresses that didn't answer on 445 within ${SCAN_MS / 1000.0} s: ${targets.size - open.size}", scanStart)
            val srcs = App.instance.store.current.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" }
            var asked = 0
            if (fresh) {
                var notFound = false
                // Each server found is identified; each home server takes the best match found now.
                val found = open.map { identify(it) }
                found.forEach { f -> ServerLog.log("vpn", "found", "smb", "", f.nbName.isNotEmpty() || f.guid.isNotEmpty(), "Server on the VPN: ${f.ip}" +
                    (if (f.nbName.isNotEmpty()) " · name ${f.nbName}" else " · name not given"),
                    "server ID ${f.guid.ifEmpty { "—" }} (read over TCP 445: SMB negotiate + NTLM challenge)") }
                for (s in srcs) {
                    // Host name first: the same NAS name as the one learned at home → that's it, its address is
                    // simply added (an alias). Then server ID / network card, as before.
                    val byName = if (s.nbName.isNotBlank()) found.firstOrNull { it.nbName.isNotEmpty() && it.nbName.equals(s.nbName, true) } else null
                    if (byName != null) ServerLog.log("vpn", "found", "smb", "", true, "${s.name.ifBlank { s.host }}: same host name ${byName.nbName} at ${byName.ip}")
                    val scored = found.map { it to likeness(it, s) }.filter { it.second > 0 }.sortedByDescending { it.second }
                    val pick = byName ?: scored.firstOrNull()?.first
                        ?: found.singleOrNull()?.takeIf { srcs.size == 1 && s.id in (useNas.value + autoFor) }   // the only server there, and you chose this NAS
                    if (pick == null) { ServerLog.log("vpn", "match", "smb", "", false, "${s.name.ifBlank { s.host }}: not found on the VPN" +
                        (if (s.nbName.isNotBlank()) " (looked for host name ${s.nbName})" else " (its host name isn't known yet — learned at home)"))
                        if (!atHome(s)) notFound = true
                        continue }
                    ServerLog.log("vpn", "match", "smb", "", true, "${s.name.ifBlank { s.host }} = ${pick.ip}" +
                        (if (byName != null) " (same host name ${byName.nbName})" else " (same server ID)"))
                    if (s.vpnHost == pick.ip) continue                               // already right
                    App.instance.store.update { x -> x.copy(sources = x.sources.map {
                        if (it.id == s.id) it.copy(vpnHost = pick.ip, altHosts = (it.altHosts + pick.ip).distinct(),
                            smbGuid = it.smbGuid.ifEmpty { pick.guid }, nbName = it.nbName.ifEmpty { pick.nbName }, mac = it.mac.ifEmpty { pick.mac }) else it }) }
                    usingVpn.remove(s.id)
                    ServerLog.log("vpn", "profile", "smb", "", true, "${s.name.ifBlank { s.host }}: found on the VPN at ${pick.ip}")
                    toast("${s.name.ifBlank { s.host }} found on the VPN at ${pick.ip}")
                }
                // Servers that match no NAS (and aren't one by an address stored): offered as a new NAS.
                val stored = App.instance.store.current.sources.flatMap { listOf(it.host, it.vpnHost) + it.altHosts }.toSet()
                val matched = srcs.mapNotNull { s -> found.firstOrNull { f -> (s.nbName.isNotBlank() && f.nbName.equals(s.nbName, true)) || likeness(f, s) >= 2 } }.map { it.ip }.toSet()
                offerNew(found.filter { it.ip !in matched && it.ip !in stored })
                searching.value = ""; autoFor = emptySet()
                // A NAS away from home still not found: ask which network to search, offering what the phone has now.
                if (notFound) askSubnet.value = subnetChoices()
                if (open.isEmpty()) { ServerLog.log("vpn", "search", "smb", "", false, "No file server found on the VPN"); toast("No file server (SMB) found on the VPN network") }
                NetIndex.recheckReachable()
                return@launch
            }
            for (ip in open) {
                if (srcs.any { it.vpnHost == ip || it.host == ip || ip in it.altHosts }) continue
                val f = identify(ip)
                val best = srcs.map { it to likeness(f, it) }.filter { it.second > 0 }.maxByOrNull { it.second }
                ServerLog.log("vpn", "found", "smb", "", true, "Server on the VPN: $ip", "guid ${f.guid} · name ${f.nbName} · mac ${f.mac}" +
                    (best?.let { " · like ${it.first.name.ifBlank { it.first.host }} (${it.second})" } ?: " · like none"))
                val s = best?.first ?: continue
                // Chosen with "Use NAS" and no VPN address yet: you already said yes — added at once.
                if (s.id in autoFor) {
                    ask.value = s to f; answer(true); autoFor = autoFor - s.id
                    toast("${s.name.ifBlank { s.host }}: VPN address $ip added")
                    continue
                }
                if ("${s.id}|$ip" in declined) continue
                // Same server ID or network card: certain — kept without a question (a short note says so).
                if (best.second >= 3 || (best.second == 2 && f.mac.isNotEmpty() && f.mac == s.mac)) {
                    ask.value = s to f; answer(true)
                    toast("${s.name.ifBlank { s.host }} found on the VPN (${f.ip}): used automatically away from home")
                    continue
                }
                // One question at a time.
                while (ask.value != null) delay(500)
                ask.value = s to f; asked++
            }
            searching.value = ""
            // Chosen servers still without a VPN address and nothing alike found: the one SMB server on the VPN that
            // isn't known yet is taken for it (you said yes to using it).
            val left = lanServers().filter { it.id in autoFor && !hasVpnAddr(it) }
            val unknown = open.filter { ip -> lanServers().none { it.vpnHost == ip || it.host == ip || ip in it.altHosts } }
            if (left.size == 1 && unknown.size == 1) {
                val f = identify(unknown[0]); ask.value = left[0] to f; answer(true)
                toast("${left[0].name.ifBlank { left[0].host }}: VPN address ${unknown[0]} added")
            } else if (left.isNotEmpty()) toast("No VPN address found for " + left.joinToString { it.name.ifBlank { it.host } } + " — Settings → VPN → Search for NAS now")
            autoFor = emptySet()
            if (open.isEmpty()) ServerLog.log("vpn", "search", "smb", "", false, "No server found on the VPN")
        }
    }

    /** "Modify": the address (and the LAN device it belongs to) as corrected by hand. */
    fun answerWith(sourceId: String, ip: String) {
        val (_, f) = ask.value ?: return
        val s = App.instance.store.current.sources.firstOrNull { it.id == sourceId } ?: return
        ask.value = s to f.copy(ip = ip.trim())
        answer(true)
    }

    /** Settings → VPN: a kept VPN address changed by hand. */
    fun setVpnHost(s: Source, ip: String) {
        App.instance.store.update { x -> x.copy(sources = x.sources.map {
            if (it.id == s.id) it.copy(vpnHost = ip.trim(), altHosts = (it.altHosts - it.vpnHost + ip.trim()).distinct()) else it }) }
        ServerLog.log("vpn", "profile", "smb", "", true, "${s.name.ifBlank { s.host }}: VPN address set by hand to ${ip.trim()}")
        usingVpn.remove(s.id)
    }

    fun answer(yes: Boolean) {
        val (s, f) = ask.value ?: return
        ask.value = null
        if (!yes) { declined += "${s.id}|${f.ip}"; return }
        App.instance.store.update { x -> x.copy(sources = x.sources.map {
            if (it.id == s.id) it.copy(vpnHost = f.ip, altHosts = (it.altHosts + f.ip).distinct(), smbGuid = it.smbGuid.ifEmpty { f.guid }, nbName = it.nbName.ifEmpty { f.nbName }, mac = it.mac.ifEmpty { f.mac }) else it }) }
        ServerLog.log("vpn", "profile", "smb", "", true, "${s.name.ifBlank { s.host }}: VPN address ${f.ip} kept (LAN ${s.host})")
        usingVpn.remove(s.id)
        scope.launch { announceSwitches() }
    }

    fun forget(s: Source) {
        App.instance.store.update { x -> x.copy(sources = x.sources.map {
            if (it.id == s.id) it.copy(vpnHost = "", altHosts = it.altHosts - it.vpnHost, addrMode = if (it.addrMode == "vpn" || it.addrMode == it.vpnHost) "auto" else it.addrMode) else it }) }
        usingVpn.remove(s.id)
    }

    private fun toast(s: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(appCtx, s, Toast.LENGTH_LONG).show() }
}
