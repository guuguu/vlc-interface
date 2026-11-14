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

    private val versions = listOf(
        "SMB311" to "SMB 3.1.1", "SMB302" to "SMB 3.0.2", "SMB300" to "SMB 3.0",
        "SMB210" to "SMB 2.1", "SMB202" to "SMB 2.0.2", "SMB1" to "SMB 1 (CIFS)",
    )

    private fun context(version: String, user: String, pass: String, domain: String, guest: Boolean): CIFSContext {
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

    suspend fun probe(path: SmbPath, user: String, pass: String, domain: String): ProbeResult = withContext(Dispatchers.IO) {
        val steps = ArrayList<ProbeStep>()
        val ip = try {
            InetAddress.getByName(path.host).hostAddress ?: ""
        } catch (e: Exception) {
            ""
        }
        steps += ProbeStep("Server", "Name \"${path.host}\" resolved", ip.isNotEmpty(), if (ip.isNotEmpty()) ip else "not found on the network")
        if (ip.isEmpty()) return@withContext ProbeResult(steps, summary = "The server name could not be found. Try its IP address.")

        val p445 = Net.reachable(ip, 445)
        val p139 = Net.reachable(ip, 139)
        steps += ProbeStep("Server", "Port 445 (direct SMB)", p445, if (p445) "open" else "closed")
        steps += ProbeStep("Server", "Port 139 (NetBIOS)", p139, if (p139) "open" else "closed")
        if (!p445 && !p139) return@withContext ProbeResult(steps, ip = ip, summary = "No SMB ports are open on this server.")

        val url = "smb://${path.host}${path.folder}/".replace("//", "/").replace("smb:/", "smb://")
        var best = ""
        var bestGuest = false
        var videos = 0
        val hasCreds = user.isNotEmpty()
        for ((v, label) in versions) {
            var ok = false
            var note = ""
            for (guest in if (hasCreds) listOf(false, true) else listOf(true)) {
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
                    note = shortReason(e)
                }
            }
            steps += ProbeStep("Protocol version", label, ok, note)
        }

        if (best.isNotEmpty()) {
            val guestOk = try {
                SmbFile(url, context(best, "", "", "", true)).exists(); true
            } catch (e: Exception) { false }
            steps += ProbeStep("Login", "Guest / anonymous", guestOk, if (guestOk) "allowed" else "denied")
            if (hasCreds) steps += ProbeStep("Login", "Saved credentials", !bestGuest, if (!bestGuest) user else "not needed")
            if (path.share.isNotEmpty()) steps += ProbeStep("Path", "Share \"${path.share}\"", true, "readable")
            if (path.path.isNotEmpty()) steps += ProbeStep("Path", "Folder \"${path.path.substringAfterLast('/')}\"", true, "$videos media files")
        }
        val label = versions.firstOrNull { it.first == best }?.second ?: ""
        ProbeResult(
            steps, best, bestGuest, ip, videos,
            if (best.isEmpty()) "No SMB version could open this path. Check the share name and the login."
            else "$label · port ${if (p445) 445 else 139} · ${if (bestGuest) "guest" else "login $user"}"
        )
    }

    private fun shortReason(e: Exception): String {
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        return when {
            "logon failure" in m || "access denied" in m || "access_denied" in m -> "access denied"
            "dialect" in m || "negotiat" in m || "not supported" in m -> "not offered by server"
            "bad network name" in m -> "share not found"
            "timed out" in m || "timeout" in m -> "timed out"
            else -> m.take(40)
        }
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
