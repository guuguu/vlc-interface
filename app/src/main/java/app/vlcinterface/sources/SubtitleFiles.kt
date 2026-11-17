package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** A subtitle file in use: a local working copy, plus where it lives next to the video (if the app can write there). */
data class SubRef(val local: File, val source: Source?, val remoteFolder: String, val remoteName: String) {
    val isRemote: Boolean get() = source != null && remoteFolder.isNotEmpty()
}

/**
 * Subtitle files next to videos: naming (video name + the subtitle's own extension), overwrite checks,
 * and writing sync corrections into the file itself (with an in-app undo copy).
 */
object SubtitleFiles {
    private val refs = HashMap<String, SubRef>()

    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, name).apply { mkdirs() }
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** "Movie.2010.1080p.mkv" + "srt" → "Movie.2010.1080p.srt" (no language code). */
    fun nameFor(item: PlayItem, ext: String): String {
        val base = Uri.decode(item.uri.trimEnd('/').substringAfterLast('/')).substringBeforeLast('.').ifEmpty { item.title.substringBeforeLast('.') }
        return "$base.${ext.lowercase()}"
    }

    fun remember(item: PlayItem, ref: SubRef) { refs[item.uri] = ref }
    fun current(item: PlayItem): SubRef? = refs[item.uri]

    private fun sourceOf(item: PlayItem): Source? = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }

    /** Where a subtitle for this video would be written, if the app can write there. */
    fun target(item: PlayItem): Pair<Source, String>? {
        val src = sourceOf(item) ?: return null
        if (!NasWriter.canWrite(src)) return null
        if (!(item.uri.startsWith("smb://") || item.uri.startsWith("http"))) return null
        return src to Urls.parent(item.uri)
    }

    suspend fun existsNextToVideo(item: PlayItem, name: String): Boolean {
        val (src, folder) = target(item) ?: return false
        return NasWriter.exists(src, folder, name)
    }

    /** Saves a downloaded subtitle under the video's name, next to the video when possible, always with a local working copy. */
    suspend fun saveDownloaded(ctx: Context, item: PlayItem, bytes: ByteArray, ext: String): Pair<SubRef, String> = withContext(Dispatchers.IO) {
        val name = nameFor(item, ext)
        val local = File(dir(ctx, "subtitles/" + hash(item.uri)), name)
        local.writeBytes(bytes)
        val t = target(item)
        val where = if (t != null) {
            val r = NasWriter.write(t.first, t.second, name, bytes, "subtitle")
            r.fold({ "Saved next to the video as $name" }, { "Kept in the app as $name (${it.message})" })
        } else "Kept in the app as $name"
        val ref = SubRef(local, t?.first, t?.second ?: "", name)
        refs[item.uri] = ref
        ref to where
    }

    /** Looks for a same-name subtitle next to a network video and makes a local working copy of it. */
    suspend fun findSidecar(ctx: Context, item: PlayItem): SubRef? = withContext(Dispatchers.IO) {
        refs[item.uri]?.let { return@withContext it }
        val src = sourceOf(item) ?: return@withContext null
        if (!(item.uri.startsWith("smb://") || item.uri.startsWith("http"))) return@withContext null
        val folder = Urls.parent(item.uri)
        val base = Uri.decode(item.uri.substringAfterLast('/')).substringBeforeLast('.')
        val list = Browse.listAll(src, folder).getOrNull() ?: return@withContext null
        val sub = list.firstOrNull { !it.isDir && Uri.decode(it.name).substringBeforeLast('.') == base && it.name.ext() in setOf("srt", "ass", "ssa", "vtt") }
            ?: return@withContext null
        val bytes = read(src, sub.uri) ?: return@withContext null
        val name = Uri.decode(sub.name)
        val local = File(dir(ctx, "subtitles/" + hash(item.uri)), name).apply { writeBytes(bytes) }
        SubRef(local, src, folder, name).also { refs[item.uri] = it }
    }

    private fun read(src: Source, uri: String): ByteArray? = try {
        when {
            uri.startsWith("smb://") -> SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain)).openInputStream().use { it.readBytes() }
            uri.startsWith("http") -> {
                val req = Request.Builder().url(uri).apply { if (src.user.isNotEmpty()) header("Authorization", Credentials.basic(src.user, src.password)) }.build()
                Net.http.newCall(req).execute().use { it.body?.bytes() }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    fun undoFile(ctx: Context, ref: SubRef) = File(dir(ctx, "subtitles-undo"), hash(ref.remoteFolder + ref.remoteName + ref.local.path) + ".orig")
    fun canUndo(ctx: Context, ref: SubRef) = undoFile(ctx, ref).exists()

    /** Shifts all timings by [ms] inside the file and saves it in place (remote copy too). Keeps the first original for undo. */
    suspend fun applyShift(ctx: Context, ref: SubRef, ms: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val undo = undoFile(ctx, ref)
            if (!undo.exists()) ref.local.copyTo(undo)
            val raw = ref.local.readBytes()
            val (text, charset) = decode(raw)
            val shifted = shift(text, ms, ref.local.name.ext())
            val out = shifted.toByteArray(charset)
            ref.local.writeBytes(out)
            if (ref.isRemote) {
                NasWriter.write(ref.source!!, ref.remoteFolder, ref.remoteName, out, "subtitle timing")
                    .fold({ Result.success("Timing saved into ${ref.remoteName}") }, { Result.success("Timing saved in the app copy (${it.message})") })
            } else Result.success("Timing saved into ${ref.local.name}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun undo(ctx: Context, ref: SubRef): Result<String> = withContext(Dispatchers.IO) {
        try {
            val undo = undoFile(ctx, ref)
            if (!undo.exists()) return@withContext Result.failure(Exception("Nothing to undo"))
            val bytes = undo.readBytes()
            ref.local.writeBytes(bytes)
            if (ref.isRemote) NasWriter.write(ref.source!!, ref.remoteFolder, ref.remoteName, bytes, "subtitle timing undo")
            undo.delete()
            Result.success("Original timing restored")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decode(b: ByteArray): Pair<String, java.nio.charset.Charset> {
        val utf8 = Charsets.UTF_8
        val s = String(b, utf8)
        return if (s.contains('�')) String(b, charset("windows-1252")) to charset("windows-1252") else s to utf8
    }

    private fun fmtSrt(t: Long, sep: Char): String {
        val v = t.coerceAtLeast(0)
        return "%02d:%02d:%02d%c%03d".format(v / 3600000, v / 60000 % 60, v / 1000 % 60, sep, v % 1000)
    }

    private fun fmtAss(t: Long): String {
        val v = t.coerceAtLeast(0)
        return "%d:%02d:%02d.%02d".format(v / 3600000, v / 60000 % 60, v / 1000 % 60, v % 1000 / 10)
    }

    fun shift(text: String, ms: Long, ext: String): String = when (ext) {
        "ass", "ssa" -> {
            val re = Regex("""^(Dialogue:\s*[^,]*,)(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),""", RegexOption.MULTILINE)
            re.replace(text) { m ->
                val g = m.groupValues
                fun t(h: String, mi: String, se: String, cs: String) = h.toLong() * 3600000 + mi.toLong() * 60000 + se.toLong() * 1000 + cs.toLong() * 10
                g[1] + fmtAss(t(g[2], g[3], g[4], g[5]) + ms) + "," + fmtAss(t(g[6], g[7], g[8], g[9]) + ms) + ","
            }
        }
        else -> {
            val re = Regex("""(\d{1,2}):(\d{2}):(\d{2})([,.])(\d{3})""")
            re.replace(text) { m ->
                val g = m.groupValues
                val t = g[1].toLong() * 3600000 + g[2].toLong() * 60000 + g[3].toLong() * 1000 + g[5].toLong()
                fmtSrt(t + ms, g[4][0])
            }
        }
    }
}
