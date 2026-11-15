package app.vlcinterface.sources

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Properties

data class SmbPath(val host: String, val share: String, val path: String) {
    val folder: String get() = "/" + listOf(share, path).filter { it.isNotEmpty() }.joinToString("/")
}

data class ProbeStep(val group: String, val label: String, val ok: Boolean, val note: String)

data class ProbeResult(
    val steps: List<ProbeStep>,
    val bestVersion: String = "",
    val guest: Boolean = false,
    val ip: String = "",
    val videoCount: Int = 0,
    val summary: String = "",
) {
    val works: Boolean get() = bestVersion.isNotEmpty()
}

/** Tries every SMB dialect, port and login type for a pasted path (jcifs-ng does the probing). */
object SmbProbe {
    /** Accepts \\host\share\dir, smb://host/share/dir, //host/share/dir or host/share/dir. */
    fun parse(input: String): SmbPath? {
        var s = input.trim().replace('\\', '/')
        s = s.removePrefix("smb://").removePrefix("cifs://")
        s = s.trimStart('/')
        if (s.isEmpty()) return null
        val parts = s.split('/').filter { it.isNotEmpty() }
        return SmbPath(parts[0], parts.getOrElse(1) { "" }, parts.drop(2).joinToString("/"))
    }

    internal val versions = listOf(
        "SMB311" to "SMB 3.1.1", "SMB302" to "SMB 3.0.2", "SMB300" to "SMB 3.0",
        "SMB210" to "SMB 2.1", "SMB202" to "SMB 2.0.2", "SMB1" to "SMB 1 (CIFS)",
    )

    internal fun context(version: String, user: String, pass: String, domain: String, guest: Boolean): CIFSContext {
        val p = Properties()
        p["jcifs.smb.client.minVersion"] = version
        p["jcifs.smb.client.maxVersion"] = version
        p["jcifs.smb.client.responseTimeout"] = "6000"
        p["jcifs.smb.client.connTimeout"] = "4000"
        p["jcifs.smb.client.soTimeout"] = "8000"
        p["jcifs.resolveOrder"] = "DNS,BCAST"
        if (version == "SMB1") p["jcifs.smb.client.useSMB2Negotiation"] = "false"
        val base = BaseContext(PropertyConfiguration(p))
        return if (guest) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(domain, user, pass))
    }

    internal fun onLanCapableNetwork(): Pair<Boolean, String> {
        return try {
            val cm = app.vlcinterface.App.instance.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false to "no network connection"
            val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            val cell = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            when {
                wifi -> true to "Wi-Fi"
                vpn -> true to "VPN"
                cell -> false to "mobile data — LAN servers work on Wi-Fi or VPN only"
                else -> true to "connected"
            }
        } catch (e: Exception) { true to "unknown" }
    }

    suspend fun probe(path: SmbPath, user: String, pass: String, domain: String): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val steps = ArrayList<ProbeStep>()
        val logUser = user
        fun log(ok: Boolean, msg: String, detail: String = "") = app.vlcinterface.data.ServerLog.log(path.host, "detect", "SMB", logUser, ok, msg, detail)

        val (netOk, netNote) = onLanCapableNetwork()
        steps += ProbeStep("Server", "Phone network", netOk, netNote)
        val ip = try {
            InetAddress.getByName(path.host).hostAddress ?: ""
        } catch (e: Exception) {
            ""
        }
        steps += ProbeStep("Server", "Name \"${path.host}\" resolved", ip.isNotEmpty(), if (ip.isNotEmpty()) ip else "not found on the network")
        if (ip.isEmpty()) {
            val why = if (!netOk) "You're on mobile data — LAN servers are reachable on Wi-Fi or VPN only" else "Can't find '${path.host}' on the network — try its IP address"
            log(false, why)
            return@withContext ProbeResult(steps, summary = why)
        }

        val p445 = Net.reachable(ip, 445)
        val p139 = Net.reachable(ip, 139)
        steps += ProbeStep("Server", "Port 445 (direct SMB)", p445, if (p445) "open" else "closed or blocked")
        steps += ProbeStep("Server", "Port 139 (NetBIOS)", p139, if (p139) "open" else "closed")
        if (!p445 && !p139) {
            val why = "No SMB ports answer on $ip — is file sharing on, and is a firewall blocking 445/139?"
            log(false, why); return@withContext ProbeResult(steps, ip = ip, summary = why)
        }

        val url = "smb://${path.host}${path.folder}/".replace("//", "/").replace("smb:/", "smb://")
        val hasCreds = user.isNotEmpty()
        val allowSmb1 = app.vlcinterface.App.instance.store.current.settings.allowSmb1
        // 1) Which dialects does the server accept? (anonymous negotiation, no share access needed)
        var best = ""
        var bestGuest = false
        var videos = 0
        var guestReason = ""
        var credReason = ""
        for ((v, label) in versions) {
            if (v == "SMB1" && !allowSmb1) { steps += ProbeStep("Protocol version", label, false, "turned off in Settings"); continue }
            var ok = false
            var note = ""
            val attempts = if (hasCreds) listOf(false, true) else listOf(true)
            for (guest in attempts) {
                try {
                    val f = SmbFile(url, context(v, user, pass, domain, guest))
                    val list = if (path.share.isEmpty()) f.list() else f.listFiles()?.map { it.name }?.toTypedArray()
                    ok = true
                    note = if (guest) "works as guest" else "works"
                    if (best.isEmpty()) {
                        best = v; bestGuest = guest
                        videos = list?.count { isPlayable(it.trimEnd('/')) } ?: 0
                    }
                    break
                } catch (e: Exception) {
                    val why = SmbErrors.explain(e, guest, path.share, path.path)
                    if (guest) guestReason = guestReason.ifEmpty { why } else credReason = credReason.ifEmpty { why }
                    note = why
                    log(false, "$label ${if (guest) "guest" else "login"}: $why", SmbErrors.ntStatus(e))
                }
            }
            steps += ProbeStep("Protocol version", label, ok, note.take(70))
        }

        // 2) Login results, reported separately.
        val bv = best.ifEmpty { "SMB311" }
        val guestOk = try { SmbFile(url, context(bv, "", "", "", true)).exists(); true } catch (e: Exception) { guestReason = SmbErrors.explain(e, true, path.share, path.path); false }
        steps += ProbeStep("Login", "Guest / anonymous", guestOk, if (guestOk) "allowed" else guestReason.ifEmpty { "not allowed" })
        if (hasCreds) {
            val credOk = best.isNotEmpty() && !bestGuest || (best.isNotEmpty() && credReason.isEmpty())
            steps += ProbeStep("Login", "User \"$user\"", credOk, if (credOk) "accepted" else credReason.ifEmpty { "not accepted" })
        } else if (!guestOk) {
            steps += ProbeStep("Login", "User and password", false, "none entered — this server needs them")
        }
        if (best.isNotEmpty()) {
            if (path.share.isNotEmpty()) steps += ProbeStep("Path", "Share \"${path.share}\"", true, "readable")
            if (path.path.isNotEmpty()) steps += ProbeStep("Path", "Folder \"${path.path.substringAfterLast('/')}\"", true, "$videos media files")
        }
        val label = versions.firstOrNull { it.first == best }?.second ?: ""
        val summary = if (best.isEmpty()) (credReason.ifEmpty { guestReason }.ifEmpty { "No SMB version could open this path" })
            else "$label · port ${if (p445) 445 else 139} · ${if (bestGuest) "guest" else "login $user"}"
        log(best.isNotEmpty(), if (best.isNotEmpty()) "Works: $summary" else "Failed: $summary")
        ProbeResult(steps, best, bestGuest, ip, videos, summary)
    }

    /** Lists a folder with jcifs-ng when libVLC's own SMB browsing is not enough (e.g. SMB1-only servers). */
    suspend fun list(host: String, folder: String, version: String, user: String, pass: String, domain: String): Result<List<Entry>> =
        withContext(Dispatchers.IO) {
            try {
                val ctx = context(version.ifEmpty { "SMB311" }, user, pass, domain, user.isEmpty())
                val url = "smb://$host/" + folder.trim('/') + "/"
                val files = SmbFile(url.replace("//$host//", "//$host/"), ctx).listFiles() ?: emptyArray()
                Result.success(files.map {
                    val name = it.name.trimEnd('/')
                    Entry(name, "smb://$host/${(folder.trim('/') + "/" + name).trim('/')}" + if (it.isDirectory) "/" else "", it.isDirectory, if (it.isDirectory) -1 else it.length())
                }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun fileContext(version: String, user: String, pass: String, domain: String): CIFSContext =
        context(version.ifEmpty { "SMB311" }, user, pass, domain, user.isEmpty())
}

/** Plain-words reasons for SMB / network failures, from the server's NT status code or the exception. */
object SmbErrors {
    fun explain(e: Throwable, guest: Boolean = false, share: String = "", folder: String = ""): String {
        val status = (e as? jcifs.smb.SmbException)?.ntStatus ?: (e.cause as? jcifs.smb.SmbException)?.ntStatus ?: 0
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        return when (status) {
            0xC000006D.toInt() -> if (guest) "This server doesn't accept guest access — enter a user and password" else "User or password not accepted"
            0xC0000072.toInt() -> "Account disabled on the server"
            0xC0000234.toInt() -> "Account locked out on the server"
            0xC0000071.toInt() -> "Password expired — change it on the server"
            0xC0000064.toInt() -> "No such user on the server"
            0xC000006E.toInt(), 0xC000006F.toInt(), 0xC0000070.toInt() -> "Account not allowed to log in now / from this device"
            0xC0000022.toInt() -> if (guest) "Guest access is not allowed on this share" else "Logged in, but this user has no access to ${if (share.isNotEmpty()) "share '$share'" else "this folder"}"
            0xC00000CC.toInt() -> "Share '${share.ifEmpty { "?" }}' doesn't exist on the server"
            0xC0000034.toInt(), 0xC000003A.toInt() -> "Folder '${folder.ifEmpty { "?" }}' not found"
            0xC00000BB.toInt() -> "The server refused this SMB version"
            0xC0000203.toInt() -> "Session closed by the server — try again"
            0xC000015B.toInt() -> "This login type isn't allowed on the server"
            else -> when {
                "logon failure" in m -> if (guest) "This server doesn't accept guest access — enter a user and password" else "User or password not accepted"
                "access is denied" in m || "access denied" in m -> if (guest) "Guest access is not allowed" else "Logged in, but no permission for this share or folder"
                "signing" in m || "signature" in m -> "Server requires signed connections"
                "encrypt" in m -> "Server requires encrypted connections (SMB 3)"
                "ntlmv1" in m || "lm compatibility" in m || "lanman" in m -> "Server only accepts an old login method"
                "dialect" in m || "negotiat" in m || "not supported" in m -> "The server refused this SMB version"
                "bad network name" in m -> "Share '${share.ifEmpty { "?" }}' doesn't exist on the server"
                "unknownhost" in m || "unknown host" in m || "unable to resolve" in m || "failed to connect: " in m && "name" in m -> "Can't find this server on the network — try its IP address"
                "connection refused" in m || "econnrefused" in m -> "The server refused the connection (port closed)"
                "timed out" in m || "timeout" in m -> "The server isn't answering — is it on, and are you on the same Wi-Fi/VPN?"
                "network is unreachable" in m || "no route" in m || "enetunreach" in m -> "No route to the server — are you on the same Wi-Fi/VPN?"
                else -> (e.message ?: e.javaClass.simpleName).take(120)
            }
        }
    }

    fun ntStatus(e: Throwable): String {
        val st = (e as? jcifs.smb.SmbException)?.ntStatus ?: 0
        return if (st != 0) "NT status 0x%08X".format(st) else e.javaClass.simpleName
    }
}
