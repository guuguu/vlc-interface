package app.vlcinterface.sources

import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only network shares.
 *
 * A share is marked read-only (per server, per share) the first time it refuses a write, or by "Check write access"
 * on the server's card. Everything that would write to a read-only share is then kept in the app instead —
 * ReplayGain values in the app's ReplayGain store, pictures in the app's pictures folder — without trying the share
 * again. "Check write access" tests every share again (a small test file written and removed) and clears or sets
 * the mark, so a share that became writable is used for writing again.
 */
object WriteGuard {
    /** The saved server a song belongs to (by its noted server, else by the host in its address). */
    fun sourceOf(sourceId: String, uri: String): Source? {
        val all = App.instance.store.current.sources
        all.firstOrNull { it.id == sourceId }?.let { return it }
        if (!uri.contains("://") || uri.startsWith("content://")) return null
        val host = uri.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
        return all.firstOrNull { it.host.substringAfter("://").substringBefore('/').substringBefore(':').equals(host, true) }
    }

    /** The share (first folder after the server) of an address. */
    fun shareOf(uri: String): String = Uri.decode(uri.substringAfter("://").substringAfter('/', "")).substringBefore('/')

    fun isReadOnly(item: PlayItem): Boolean = isReadOnly(item.sourceId, item.uri)
    fun isReadOnly(sourceId: String, uri: String): Boolean {
        val s = sourceOf(sourceId, uri) ?: return false
        val share = shareOf(uri)
        return s.readOnly.any { it.equals(share, true) } || "*" in s.readOnly
    }

    /** A refusal that means "no writing here" (not a passing network problem). */
    fun isDenied(e: Throwable): Boolean {
        val st = (e as? jcifs.smb.SmbException)?.ntStatus ?: (e.cause as? jcifs.smb.SmbException)?.ntStatus ?: 0
        if (st == 0xC0000022.toInt() || st == 0xC00000A2.toInt() || st == 0xC0000061.toInt()) return true
        val m = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return "access is denied" in m || "access denied" in m || "read-only" in m || "read only" in m || "write protect" in m ||
            "http 403" in m || "http 405" in m
    }

    /** Marks the share of [uri] read-only (from now on writes there are kept in the app). */
    fun markReadOnly(sourceId: String, uri: String, why: String) {
        val s = sourceOf(sourceId, uri) ?: return
        val share = shareOf(uri).ifEmpty { "*" }
        if (s.readOnly.any { it.equals(share, true) }) return
        App.instance.store.update { x -> x.copy(sources = x.sources.map { if (it.id == s.id) it.copy(readOnly = it.readOnly + share) else it }) }
        ServerLog.log(s.host, "write", s.proto.uppercase(), s.user, false,
            "Share \"$share\" is read-only ($why) — from now on writes are kept in the app; \"Check write access\" tests it again")
    }

    /**
     * Tests writing on every share of [s] the app knows of (its pinned folders and its own path): a small test file
     * is created and removed. Returns one line per share; the read-only marks are updated to match.
     */
    suspend fun check(s: Source): List<String> = withContext(Dispatchers.IO) {
        if (s.proto != "smb") return@withContext listOf("Only SMB servers can be tested")
        val shares = (s.pins.map { it.trim('/').substringBefore('/') } + s.path.trim('/').substringBefore('/') + s.readOnly)
            .filter { it.isNotBlank() && it != "*" }.distinct()
        if (shares.isEmpty()) return@withContext listOf("No share known yet — pin a folder (★) first")
        val cx = SmbProbe.fileContext(s.smbVersion, s.user, s.password, s.domain)
        val host = s.host.substringAfter("://").trimEnd('/')
        val ro = ArrayList<String>(); val lines = ArrayList<String>()
        for (sh in shares) {
            val f = SmbFile("smb://$host/$sh/.vlci-write-test-${System.currentTimeMillis()}", cx)
            val result = try {
                f.createNewFile(); f.delete(); null
            } catch (e: Exception) { e }
            if (result == null) lines += "$sh: writable"
            else if (isDenied(result)) { ro += sh; lines += "$sh: read-only" }
            else lines += "$sh: could not test (${SmbErrors.explain(result)})"
        }
        val keepUntested = s.readOnly.filter { r -> r != "*" && lines.none { it.startsWith("$r:") && !it.contains("could not test") } && r !in shares }
        App.instance.store.update { x -> x.copy(sources = x.sources.map { if (it.id == s.id) it.copy(readOnly = (ro + keepUntested).distinct()) else it }) }
        ServerLog.log(s.host, "write", "SMB", s.user, ro.isEmpty(), "Write access check: " + lines.joinToString("; "))
        lines
    }
}
