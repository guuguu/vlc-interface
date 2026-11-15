package app.vlcinterface.sources

import app.vlcinterface.data.ServerLog
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** One thing found on the server, plus what is still needed to use it. */
data class Service(
    val proto: String,          // smb · nfs · sftp · ftp · ftps · webdav · http · upnp
    val label: String,
    val port: Int,
    val ok: Boolean,            // usable now with what was given
    val found: String,          // what the check saw
    val needs: List<String>,    // what is still missing (empty = nothing)
    val shares: List<String> = emptyList(),
    val hiddenShares: Int = 0,
    val smbVersions: List<String> = emptyList(),
    val guest: Boolean = false,
    val address: String = "",   // for WebDAV/HTTP: base address to save
)

data class CheckResult(
    val host: String,
    val ip: String,
    val name: String,
    val network: String,
    val networkOk: Boolean,
    val reachable: Boolean,
    val services: List<Service>,
    val summary: String,
)

/**
 * "What do I need to connect to this server?" — given only a host name or IP:
 * resolves it, looks at which file-sharing services answer, and for each says
 * what is still missing (login, share, path, SMB1 switch…).
 */
object ServerCheck {
    private val ports = listOf(
        445 to "smb", 139 to "smb", 2049 to "nfs", 22 to "sftp", 21 to "ftp", 990 to "ftps",
        80 to "http", 443 to "https", 5005 to "http", 5006 to "https", 8080 to "http", 8443 to "https",
        8200 to "dlna", 32400 to "plex", 8096 to "jellyfin",
    )

    suspend fun run(input: String, user: String, pass: String, domain: String): CheckResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val host = input.trim().replace('\\', '/').replace(Regex("^[a-zA-Z0-9+.-]+://"), "").trimStart('/')
            .substringBefore('/').substringAfterLast('@').let { if (it.count { c -> c == ':' } == 1) it.substringBefore(':') else it }
        fun log(ok: Boolean, msg: String, proto: String = "") = ServerLog.log(host, "check", proto, user, ok, msg, startedAt = started)

        val (netOk, net) = SmbProbe.onLanCapableNetwork()
        val addr = try { InetAddress.getByName(host) } catch (_: Exception) { null }
        if (addr == null) {
            val why = if (!netOk) "You're on $net" else "Can't find \"$host\" on the network — check the name or use its IP address"
            log(false, why)
            return@withContext CheckResult(host, "", "", net, netOk, false, emptyList(), why)
        }
        val ip = addr.hostAddress ?: host
        val name = try { addr.canonicalHostName.takeIf { it != ip } ?: "" } catch (_: Exception) { "" }

        // Which services answer (all ports at once).
        val open = coroutineScope {
            ports.map { (p, _) -> async { p to Net.reachable(ip, p, 1200) } }.awaitAll()
        }.filter { it.second }.map { it.first }.toSet()
        log(open.isNotEmpty(), if (open.isEmpty()) "No file-sharing port answers on $ip" else "Open ports on $ip: ${open.sorted().joinToString()}")
        if (open.isEmpty()) {
            val why = "$ip answers no file-sharing port (SMB 445/139, NFS 2049, SFTP 22, FTP 21, WebDAV/HTTP). " +
                "Is the server on, file sharing enabled, and no firewall blocking the phone?"
            return@withContext CheckResult(host, ip, name, net, netOk, false, emptyList(), why)
        }

        val services = ArrayList<Service>()
        if (445 in open || 139 in open) services += smb(host, ip, open, user, pass, domain).also { log(it.ok, "SMB: ${it.found}" + needsText(it), "SMB") }
        if (2049 in open) services += Service("nfs", "NFS", 2049, true, "NFS server answers (no login used)",
            listOf("The export path, e.g. /volume1/media (from the NAS's NFS settings)", "The NAS must allow this phone's IP ($ip's network) in the NFS rules"))
        if (22 in open) services += banner(ip, 22).let { b ->
            Service("sftp", "SFTP (SSH)", 22, user.isNotEmpty(), if (b.isNotEmpty()) "SSH server: $b" else "SSH port open",
                listOfNotNull(if (user.isEmpty()) "User and password (the server's SSH / SFTP account)" else null, "Optional: start folder, e.g. /volume1/media"))
        }
        if (21 in open || 990 in open) {
            val p = if (21 in open) 21 else 990
            val b = if (p == 21) banner(ip, 21) else ""
            services += Service(if (p == 21) "ftp" else "ftps", if (p == 21) "FTP" else "FTPS", p, user.isNotEmpty(),
                if (b.isNotEmpty()) "FTP server: $b" else "FTP port open",
                listOfNotNull(if (user.isEmpty()) "User and password (or \"anonymous\" if the server allows it)" else null,
                    if (p == 21) "Plain FTP is unencrypted — fine at home only" else null))
        }
        open.filter { it in listOf(80, 443, 5005, 5006, 8080, 8443) }.sorted().forEach { p ->
            web(ip, host, p, user, pass)?.let { services += it; log(it.ok, "${it.label}: ${it.found}" + needsText(it), it.proto.uppercase()) }
        }
        if (8200 in open) services += Service("upnp", "DLNA media server", 8200, true, "DLNA server (MiniDLNA / Media Server) on port 8200",
            listOf("Nothing to type: it appears under \"Found on your LAN\" — tap Save as preset there"))
        if (32400 in open) services += Service("info", "Plex", 32400, false, "Plex Media Server runs here",
            listOf("Plex isn't a file share. Use SMB/NFS of the same NAS for the files, or enable Plex's DLNA server"))
        if (8096 in open) services += Service("info", "Jellyfin / Emby", 8096, false, "Jellyfin or Emby runs here",
            listOf("Not a file share. Use SMB/NFS of the same machine, or enable its DLNA plugin"))

        val usable = services.filter { it.ok && it.proto != "info" }
        val summary = when {
            usable.isNotEmpty() -> "Ready: " + usable.joinToString(" · ") { it.label }
            services.any { it.needs.isNotEmpty() } -> "Reachable — see below what's still needed"
            else -> "Reachable, but no service the app can use"
        }
        log(usable.isNotEmpty(), summary)
        CheckResult(host, ip, name, net, netOk, true, services, summary)
    }

    private fun needsText(s: Service) = if (s.needs.isEmpty()) "" else " — needs: " + s.needs.joinToString("; ")

    /** SMB: which versions the server speaks, whether guest works, and the share list. */
    private fun smb(host: String, ip: String, open: Set<Int>, user: String, pass: String, domain: String): Service {
        val allowSmb1 = app.vlcinterface.App.instance.store.current.settings.allowSmb1
        val spoken = ArrayList<String>()
        var listing: Array<String>? = null
        var guestOk = false
        var loginOk = false
        var loginWhy = ""
        var guestWhy = ""
        for ((v, label) in SmbProbe.versions) {
            if (v == "SMB1" && !allowSmb1) continue
            // Guest first, then the given login.
            for (guest in if (user.isNotEmpty()) listOf(true, false) else listOf(true)) {
                try {
                    val l = SmbFile("smb://$host/", SmbProbe.context(v, user, pass, domain, guest)).list()
                    if (label !in spoken) spoken += label
                    if (guest) guestOk = true else loginOk = true
                    if (listing == null || !guest) listing = l
                } catch (e: Exception) {
                    // A login/permission error still means the server speaks this version.
                    val auth = e is SmbAuthException || (e as? SmbException)?.ntStatus.let { it == 0xC000006D.toInt() || it == 0xC0000022.toInt() }
                    if (auth && label !in spoken) spoken += label
                    val why = SmbErrors.explain(e, guest)
                    if (auth) { if (guest) guestWhy = why else loginWhy = why }
                }
            }
            if (loginOk || (guestOk && user.isEmpty())) break   // enough found; skip slow older versions
        }
        val all = listing?.map { it.trimEnd('/') } ?: emptyList()
        val shares = all.filterNot { it.endsWith("$") }
        val needs = ArrayList<String>()
        when {
            spoken.isEmpty() && !allowSmb1 -> needs += "Probably SMB1 only: turn on Settings → Network → Allow SMB1, then check again"
            spoken.isEmpty() -> needs += "The server refused every SMB version — check its SMB settings"
            user.isEmpty() && !guestOk -> needs += "User and password — this server doesn't allow guest access"
            user.isNotEmpty() && !loginOk -> needs += loginWhy.ifEmpty { "User or password not accepted" }
        }
        if (shares.isNotEmpty()) needs += "Pick a share below"
        else if (spoken.isNotEmpty() && (guestOk || loginOk)) needs += "No visible shares — type the share name (it may be hidden)"
        val port = if (445 in open) 445 else 139
        val found = buildString {
            append(if (spoken.isEmpty()) "SMB port $port open" else spoken.joinToString(", "))
            append(" · port $port")
            if (guestOk) append(" · guest allowed") else if (guestWhy.isNotEmpty() || user.isEmpty()) append(" · guest not allowed")
            if (loginOk) append(" · login accepted")
            if (shares.isNotEmpty()) append(" · ${shares.size} shares")
        }
        return Service("smb", "SMB / Windows share", port, (guestOk || loginOk) && shares.isNotEmpty(), found, needs,
            shares, all.size - shares.size, spoken, guestOk && !loginOk)
    }

    /** HTTP/HTTPS port: WebDAV (needs login?) or just a web page. */
    private fun web(ip: String, host: String, port: Int, user: String, pass: String): Service? {
        val https = port in listOf(443, 5006, 8443)
        val base = (if (https) "https://" else "http://") + host + if (port == 80 || port == 443) "" else ":$port"
        return try {
            val client = Net.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()
            val req = Request.Builder().url("$base/").method("PROPFIND", ByteArray(0).toRequestBody(null))
                .header("Depth", "0").header("User-Agent", Net.UA)
                .apply { if (user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(user, pass)) }.build()
            client.newCall(req).execute().use { r ->
                val dav = r.header("DAV") != null || r.code == 207
                when {
                    dav || (r.code == 401 && port in listOf(5005, 5006)) -> Service("webdav", "WebDAV (${if (https) "HTTPS" else "HTTP"} $port)", port,
                        r.code == 207, if (r.code == 207) "WebDAV answers, login accepted" else "WebDAV server (HTTP ${r.code})",
                        listOfNotNull(if (r.code == 401) "User and password" else null, if (!https) "Unencrypted — prefer the HTTPS port if there is one" else null),
                        address = base)
                    r.code == 401 -> Service("http", "Web server ($port)", port, false, "Web page asks for a login (HTTP 401) — may be WebDAV or a NAS admin page",
                        listOf("If it's WebDAV: user and password, then save as WebDAV"), address = base)
                    else -> null  // ordinary web page (NAS admin etc.): not shown
                }
            }
        } catch (_: Exception) { null }
    }

    /** First line the server sends (SSH / FTP greet with a version line). */
    private fun banner(ip: String, port: Int): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1500); s.soTimeout = 1500
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim()?.take(60) ?: ""
        }
    } catch (_: Exception) { "" }
}
