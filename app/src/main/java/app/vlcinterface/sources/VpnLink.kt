package app.vlcinterface.sources

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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n) ?: continue
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
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
                        if (ip !in self) { vpn += Net(ip, ia.networkPrefixLength.toInt()); self += ip }
                    }
                }
            } catch (_: Exception) {}
            val wasUp = vpnUp.value
            homeNets = home; vpnNets = vpn; vpnSelf = self
            vpnUp.value = vpn.isNotEmpty()
            // Same storage, other road: the index (kept by server, with LAN addresses) is used as it is — only who
            // answers now is checked again; nothing is indexed anew.
            NetIndex.recheckReachable()
            learnLocal()
            announceSwitches()
            if (vpnUp.value && !wasUp) {
                ServerLog.log("vpn", "up", "vpn", "", true, "VPN / tunnel up: " + self.joinToString { ipStr(it) })
                if (on()) discover()
            }
            if (!vpnUp.value && wasUp) ServerLog.log("vpn", "down", "vpn", "", true, "VPN / tunnel down")
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
        "auto", "" -> if (on() && s.vpnHost.isNotBlank() && vpnUp.value && !onLan(s.host)) s.vpnHost else s.host
        else -> if (s.addrMode in s.altHosts) s.addrMode else s.host
    }

    fun useVpn(s: Source): Boolean = hostNow(s) != s.host

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

    /** "Not this session": network songs (LAN servers) are skipped until the phone is on their network again. */
    @Volatile var ignoreSession = false; private set
    @Volatile private var retryAt = 0L

    /** On a network of [addr]: one of the phone's addresses (Wi-Fi, VPN …) is on the same subnet. */
    private fun onSubnetOf(addr: String): Boolean { val ip = ipInt(addr) ?: return true; return (homeNets + vpnNets).any { it.contains(ip) } }

    /** The phone is on a subnet of [s]'s LAN address or of any stored one. */
    fun nearby(s: Source): Boolean = onSubnetOf(s.host) || s.altHosts.any { onSubnetOf(it) } || (s.vpnHost.isNotBlank() && onSubnetOf(s.vpnHost))

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
        val st = App.instance.store.current
        val servers = st.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" && isIpLiteral(it.host) }
        val away = servers.filter { !nearby(it) }
        if (away.isEmpty()) { askedStartFor = ""; ignoreSession = false; return }        // back near a server: all as usual
        if (!on() || !st.settings.vpnAskStart || ignoreSession) return
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

    /** "Not this session": network songs skipped until the phone is near a server again. */
    fun skipSession() {
        askStart.value = null; ignoreSession = true
        ServerLog.log("vpn", "session", "smb", "", true, "Network songs skipped for this session (no VPN)")
    }

    /** Opens the VPN app (ZeroTier when installed, else Android's VPN settings) — it can't be switched on from here. */
    fun startVpn(ctx: Context): String {
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

    /** NetBIOS node status (UDP 137): the server's name and MAC. */
    private fun netbios(ip: String): Pair<String, String> {
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 1200
                val q = ByteArray(50)
                q[0] = 0x12; q[1] = 0x34; q[5] = 1                        // id, 1 question
                q[12] = 0x20; q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()
                for (i in 15 until 45) q[i] = 'A'.code.toByte()
                q[45] = 0; q[46] = 0; q[47] = 0x21; q[48] = 0; q[49] = 1   // NBSTAT, IN
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size); s.receive(p)
                var i = 12 + 34 + 4 + 4 + 2                                   // header, name, type/class, ttl, rdlength
                val n = buf[i].toInt() and 0xFF; i++
                var name = ""
                for (k in 0 until n) {
                    val nm = String(buf, i, 15, Charsets.ISO_8859_1).trim(); val suffix = buf[i + 15].toInt() and 0xFF
                    val group = (buf[i + 16].toInt() and 0x80) != 0
                    if (name.isEmpty() && suffix == 0 && !group) name = nm
                    i += 18
                }
                val mac = (0 until 6).joinToString(":") { "%02x".format(buf[i + it]) }
                return name to (if (mac == "00:00:00:00:00:00") "" else mac)
            }
        } catch (_: Exception) { return "" to "" }
    }

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

    fun identify(ip: String): Found { val (nb, mac) = netbios(ip); return Found(ip, smbGuid(ip), nb, mac) }

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
    fun discover() {
        scope.launch {
            if (searching.value.isNotEmpty()) return@launch
            val nets = vpnNets
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
            searching.value = "Searching the VPN for servers… (${targets.size} addresses)"
            val sem = Semaphore(64)
            val open = targets.map { ip -> async { sem.withPermit { if (reachable(ipStr(ip), 445, 400)) ipStr(ip) else null } } }.awaitAll().filterNotNull()
            val srcs = App.instance.store.current.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" }
            var asked = 0
            for (ip in open) {
                if (srcs.any { it.vpnHost == ip || it.host == ip || ip in it.altHosts }) continue
                val f = identify(ip)
                val best = srcs.map { it to likeness(f, it) }.filter { it.second > 0 }.maxByOrNull { it.second }
                ServerLog.log("vpn", "found", "smb", "", true, "Server on the VPN: $ip", "guid ${f.guid} · name ${f.nbName} · mac ${f.mac}" +
                    (best?.let { " · like ${it.first.name.ifBlank { it.first.host }} (${it.second})" } ?: " · like none"))
                val s = best?.first ?: continue
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
