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

    /**
     * The video is gone: the app's own copies of its downloaded subtitles go too (working and undo copies).
     * Subtitle files on the NAS are left alone; [written] only helps find the undo copies.
     */
    suspend fun forgetVideo(ctx: Context, uri: String, written: List<Triple<String, String, String>>, downloaded: List<String>) = withContext(Dispatchers.IO) {
        refs.remove(uri)
        // Only on the phone, and only the subtitles the app downloaded (OpenSubtitles): their copies in the app and
        // their undo copies. Copies of subtitles found next to a video are left alone.
        val d = File(ctx.filesDir, "subtitles/" + hash(uri))
        val undo = File(ctx.filesDir, "subtitles-undo")
        downloaded.forEach { name ->
            val f = File(d, name)
            (written.map { it.second } + "").forEach { fo -> File(undo, hash(fo + f.name + f.path) + ".orig").delete() }
            f.delete()
        }
        if (d.list()?.isEmpty() == true) d.delete()
        // Subtitle files on the NAS are never deleted (user rule): only the app's own copies go.
    }
    fun current(item: PlayItem): SubRef? = refs[item.uri]

    private fun sourceOf(item: PlayItem): Source? = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }

    /** Where a subtitle for this video would be written, if the app can write there. */
    fun target(item: PlayItem): Pair<Source, String>? {
        val src = sourceOf(item) ?: return null
        if (!NasWriter.canWrite(src)) return null
        if (!(item.uri.startsWith("smb://") || item.uri.startsWith("http"))) return null
        return src to Urls.parent(item.uri)
    }

    /**
     * A name for a downloaded subtitle that's free next to the video (2026-11-21 16:33): the video's name, else with the
     * language ("Movie.es.srt"), else numbered ("Movie.es.2.srt" …) — an existing subtitle is never overwritten.
     */
    suspend fun freeName(item: PlayItem, ext: String, lang: String): String {
        val first = nameFor(item, ext)
        if (!existsNextToVideo(item, first)) return first
        val base = first.substringBeforeLast('.'); val e = ext.lowercase(); val l = lang.lowercase().ifEmpty { "sub" }
        if (!existsNextToVideo(item, "$base.$l.$e")) return "$base.$l.$e"
        for (n in 2..20) if (!existsNextToVideo(item, "$base.$l.$n.$e")) return "$base.$l.$n.$e"
        return "$base.$l.${System.currentTimeMillis() / 1000}.$e"
    }

    suspend fun existsNextToVideo(item: PlayItem, name: String): Boolean {
        val (src, folder) = target(item) ?: return false
        return NasWriter.exists(src, folder, name)
    }

    /** Saves a downloaded subtitle under the video's name, next to the video when possible, always with a local working copy. */
    suspend fun saveDownloaded(ctx: Context, item: PlayItem, bytes: ByteArray, ext: String, name0: String? = null): Pair<SubRef, String> = withContext(Dispatchers.IO) {
        val name = name0 ?: nameFor(item, ext)
        val local = File(dir(ctx, "subtitles/" + hash(item.uri)), name)
        local.writeBytes(bytes)
        // Remembered as downloaded by the app (OpenSubtitles): only these are deleted when the video is gone.
        app.vlcinterface.player.VideoAuto.addDownloaded(item.uri, name)
        val t = target(item)
        val where = if (t != null) {
            val r = NasWriter.write(t.first, t.second, name, bytes, "subtitle")
            // Remembered with the video, so it's deleted together with the video's data when the video is gone.
            if (r.isSuccess) app.vlcinterface.player.VideoAuto.addSub(item.uri, t.first.id, t.second, name)
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

    /**
     * Subtitles one folder level down (2026-11-21 21:11: films whose subtitles sit in a subfolder that can't be browsed
     * by hand, e.g. "Movie/Subs/2_English.srt"). In each subfolder of the video's folder, a subtitle file is taken when
     * its name starts with the video's name, or when the subfolder itself is a subtitle folder (Subs, Subtitles, Sub,
     * Subtitulos …) or is named after the video — there every subtitle file counts. At most 12 subfolders and 24 files
     * (500 KB each); copied into the app (for the player and for sync saving back into the file).
     * Network videos (SMB / web) and phone videos with a real file path.
     */
    private val subRefs = HashMap<String, List<SubRef>>()
    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")
    private val SUB_DIR = Regex("""(?i)^(subs?|subtitles?|subtitulos?|sottotitoli|untertitel|legendas?|sous[- ]?titres?|subtitrari)$""")

    suspend fun findInSubfolders(ctx: Context, item: PlayItem, phonePath: String?): List<SubRef> = withContext(Dispatchers.IO) {
        subRefs[item.uri]?.let { return@withContext it }
        val base = Uri.decode(item.uri.substringAfterLast('/')).substringBeforeLast('.')
        fun norm(x: String) = x.lowercase().replace(Regex("""[\s._\-]+"""), " ").trim()
        val nb = norm(base)
        fun wanted(dirName: String, fileName: String): Boolean {
            if (fileName.ext() !in SUB_EXT) return false
            val d = Uri.decode(dirName).trimEnd('/')
            return SUB_DIR.matches(d) || norm(d) == nb || norm(Uri.decode(fileName).substringBeforeLast('.')).startsWith(nb)
        }
        val out = ArrayList<SubRef>()
        val store = dir(ctx, "subtitles/" + hash(item.uri) + "/sub")
        try {
            val src = sourceOf(item)
            if (src != null && (item.uri.startsWith("smb://") || item.uri.startsWith("http"))) {
                val list = Browse.listAll(src, Urls.parent(item.uri), "subtitles").getOrNull() ?: emptyList()
                for (d in list.filter { it.isDir }.take(12)) {
                    if (out.size >= 24) break
                    val files = Browse.listAll(src, d.uri, "subtitles").getOrNull() ?: continue
                    for (f in files.filter { !it.isDir && wanted(d.name, it.name) && (it.size <= 0 || it.size <= 500_000) }) {
                        if (out.size >= 24) break
                        val bytes = read(src, f.uri) ?: continue
                        val name = Uri.decode(f.name)
                        // The copy is named "<subfolder> - <file>" so two "English.srt" in different subfolders don't clash.
                        val local = File(store, (Uri.decode(d.name).trimEnd('/') + " - " + name).replace('/', '_')).apply { writeBytes(bytes) }
                        out += SubRef(local, src, d.uri, name)
                    }
                }
            } else if (phonePath != null) {
                val folder = File(phonePath).parentFile
                folder?.listFiles()?.filter { it.isDirectory }?.take(12)?.forEach { d ->
                    // libVLC already loads files in Subs / Subtitles whose name contains the video's: not added twice.
                    d.listFiles()?.filter { it.isFile && wanted(d.name, it.name) && it.length() <= 500_000 &&
                        !(SUB_DIR.matches(d.name) && norm(it.name.substringBeforeLast('.')).contains(nb)) }?.forEach { f ->
                        if (out.size < 24) out += SubRef(f, null, "", f.name)
                    }
                }
            }
        } catch (_: Exception) {}
        out.also { subRefs[item.uri] = it }
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
