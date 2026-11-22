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
     * Subtitles one folder level down and in season archives (2026-11-21 21:11 / 21:16), e.g. "Movie/Subs/2_English.srt"
     * or "Show/Subs/Show.S02E05.srt" or "Show/Show.S02.subs.zip". Only files that can really be told to belong to the
     * playing video are offered:
     *  - a file named like the video (its name starts with the video's name), or carrying the same episode code
     *    (S02E05, 2x05, same series or no series name);
     *  - any subtitle file in a subfolder named after the video, or in a subtitle folder (Subs, Subtitles …) when the
     *    video's folder holds only this one video (a film folder); with several videos there (a season), bulk subtitles
     *    aren't offered unless named as above;
     *  - .zip archives whose name has this video's season (S02, Season 2), in the video's folder or one level down:
     *    the entries for this episode (episode code) — a whole season's subtitles in one archive.
     * At most 12 subfolders, 24 files (500 KB each), archives up to 20 MB. Copied into the app.
     * Network videos (SMB / web) and phone videos with a real file path.
     */
    private val subRefs = HashMap<String, List<SubRef>>()
    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "mts", "mpg", "mpeg", "webm", "flv", "vob", "3gp", "divx", "ogv")
    private val SUB_DIR = Regex("""(?i)^(subs?|subtitles?|subtitulos?|sottotitoli|untertitel|legendas?|sous[- ]?titres?|subtitrari)$""")

    private fun norm(x: String) = x.lowercase().replace(Regex("""[\s._\-]+"""), " ").trim()

    /** The season in an archive's name: S02 / s2 / Season 2 / Temporada 2 / Staffel 2 / Saison 2 / Stagione 2. */
    fun seasonOf(name: String): Int? {
        val n = Uri.decode(name).substringBeforeLast('.')
        Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})(?:[^0-9e]|$)""").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("""(?i)(?:season|temporada|staffel|saison|stagione|sezon)[\s._-]*(\d{1,2})""").find(n)?.let { return it.groupValues[1].toInt() }
        return null
    }

    suspend fun findInSubfolders(ctx: Context, item: PlayItem, phonePath: String?): List<SubRef> = withContext(Dispatchers.IO) {
        subRefs[item.uri]?.let { return@withContext it }
        val vName = Uri.decode(item.uri.substringAfterLast('/'))
        val base = vName.substringBeforeLast('.')
        val nb = norm(base)
        val ep = app.vlcinterface.player.Episodes.parse(vName)
        /** The file belongs to this video by its name: same name start, or the same episode (series matching or absent). */
        fun named(file: String): Boolean {
            val f = Uri.decode(file)
            if (norm(f.substringBeforeLast('.')).startsWith(nb)) return true
            if (ep == null) return false
            val e = app.vlcinterface.player.Episodes.parse(f) ?: return false
            return e.season == ep.season && e.episode == ep.episode && (e.series.isEmpty() || e.series == ep.series || ep.series.contains(e.series) || e.series.contains(ep.series))
        }
        val out = ArrayList<SubRef>()
        val store = dir(ctx, "subtitles/" + hash(item.uri) + "/sub")
        fun keep(fileName: String, bytes: ByteArray, from: String, src: Source?, remoteFolder: String, remoteName: String) {
            if (out.size >= 24 || bytes.size > 500_000) return
            val local = File(store, ("$from - $fileName").replace('/', '_')).apply { writeBytes(bytes) }
            out += SubRef(local, src, remoteFolder, remoteName)
        }
        /** This episode's subtitles out of a season archive. */
        fun fromZip(zipName: String, bytes: ByteArray) {
            if (ep == null) return
            try {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { z ->
                    var e = z.nextEntry
                    while (e != null && out.size < 24) {
                        val en = e.name.substringAfterLast('/')
                        if (!e.isDirectory && en.ext() in SUB_EXT && named(en)) keep(en, z.readBytes(), Uri.decode(zipName).substringBeforeLast('.'), null, "", en)
                        e = z.nextEntry
                    }
                }
            } catch (_: Exception) {}
        }
        fun seasonZip(name: String) = ep != null && name.ext() == "zip" && (seasonOf(name) == ep.season || named(name))
        try {
            val src = sourceOf(item)
            if (src != null && (item.uri.startsWith("smb://") || item.uri.startsWith("http"))) {
                val list = Browse.listAll(src, Urls.parent(item.uri), "subtitles").getOrNull() ?: emptyList()
                val oneVideo = list.count { !it.isDir && it.name.ext() in VIDEO_EXT } <= 1
                // Next to the video: this episode's file under another name (the exact same name is the sidecar), season archives.
                for (f in list.filter { !it.isDir }) {
                    val fn = Uri.decode(f.name)
                    if (fn.ext() in SUB_EXT && fn.substringBeforeLast('.') != base && ep != null && named(fn)) read(src, f.uri)?.let { keep(fn, it, "here", src, Urls.parent(item.uri), fn) }
                    else if (seasonZip(fn) && (f.size <= 0 || f.size <= 20_000_000)) read(src, f.uri)?.let { fromZip(fn, it) }
                }
                for (d in list.filter { it.isDir }.take(12)) {
                    if (out.size >= 24) break
                    val dn = Uri.decode(d.name).trimEnd('/')
                    val all = norm(dn) == nb || (SUB_DIR.matches(dn) && oneVideo)
                    val files = Browse.listAll(src, d.uri, "subtitles").getOrNull() ?: continue
                    for (f in files.filter { !it.isDir }) {
                        if (out.size >= 24) break
                        val fn = Uri.decode(f.name)
                        if (fn.ext() in SUB_EXT && (all || named(fn)) && (f.size <= 0 || f.size <= 500_000)) read(src, f.uri)?.let { keep(fn, it, dn, src, d.uri, fn) }
                        else if (seasonZip(fn) && (f.size <= 0 || f.size <= 20_000_000)) read(src, f.uri)?.let { fromZip(fn, it) }
                    }
                }
            } else if (phonePath != null) {
                val folder = File(phonePath).parentFile
                val list = folder?.listFiles()?.toList() ?: emptyList()
                val oneVideo = list.count { it.isFile && it.name.ext() in VIDEO_EXT } <= 1
                for (f in list.filter { it.isFile }) {
                    // Same-folder files with this video's name are found by libVLC itself; other names with this episode's code are added.
                    if (f.name.ext() in SUB_EXT && ep != null && !norm(f.name.substringBeforeLast('.')).contains(nb) && named(f.name) && f.length() <= 500_000) out += SubRef(f, null, "", f.name)
                    else if (seasonZip(f.name) && f.length() <= 20_000_000) fromZip(f.name, f.readBytes())
                }
                for (d in list.filter { it.isDirectory }.take(12)) {
                    val all = norm(d.name) == nb || (SUB_DIR.matches(d.name) && oneVideo)
                    d.listFiles()?.filter { it.isFile }?.forEach { f ->
                        if (out.size >= 24) return@forEach
                        // libVLC already loads files in Subs / Subtitles whose name contains the video's: not added twice.
                        if (f.name.ext() in SUB_EXT && (all || named(f.name)) && f.length() <= 500_000 &&
                            !(SUB_DIR.matches(d.name) && norm(f.name.substringBeforeLast('.')).contains(nb))) out += SubRef(f, null, "", f.name)
                        else if (seasonZip(f.name) && f.length() <= 20_000_000) fromZip(f.name, f.readBytes())
                    }
                }
            }
        } catch (_: Exception) {}
        out.also { subRefs[item.uri] = it }
    }

    // ---------- in-app subtitle browser (2026-11-21 21:17): the movie's folder, without Android's file picker ----------

    /** The file name belongs to this video: same name start, or the same episode code (series matching or absent). */
    fun belongs(item: PlayItem, file: String): Boolean {
        val vName = Uri.decode(item.uri.substringAfterLast('/'))
        val nb = norm(vName.substringBeforeLast('.'))
        val f = Uri.decode(file)
        if (norm(f.substringBeforeLast('.')).startsWith(nb)) return true
        val ep = app.vlcinterface.player.Episodes.parse(vName) ?: return false
        val e = app.vlcinterface.player.Episodes.parse(f) ?: return false
        return e.season == ep.season && e.episode == ep.episode && (e.series.isEmpty() || e.series == ep.series || ep.series.contains(e.series) || e.series.contains(ep.series))
    }

    fun isSub(name: String) = name.ext() in SUB_EXT
    fun isZip(name: String) = name.ext() == "zip"
    /** A zip named for a season (Show.S02.Subs.zip, Season 2.zip …): a season's subtitles. */
    fun isSeasonArchive(name: String) = isZip(name) && seasonOf(name) != null

    /** One entry of the browser: a folder, a subtitle file, or a zip archive (its subtitles listed when opened). */
    data class BEntry(val name: String, val uri: String, val kind: Char)   // 'd' folder, 's' subtitle, 'z' zip

    /** Where the browser starts and how far up it may go: (source or null for the phone, start folder, top folder). */
    fun browseStart(item: PlayItem, phonePath: String?): Triple<Source?, String, String>? {
        val src = sourceOf(item)
        if (src != null && (item.uri.startsWith("smb://") || item.uri.startsWith("http"))) {
            // Up as far as the share itself (smb://host/share/), not only the location's saved path (21:20); web: the site.
            val u = item.uri; val hostEnd = u.indexOf('/', u.indexOf("://") + 3).let { if (it < 0) u.length else it }
            val top = if (u.startsWith("smb://")) u.substring(0, hostEnd) + "/" + u.substring(hostEnd).trim('/').substringBefore('/') + "/" else u.substring(0, hostEnd) + "/"
            return Triple(src, Urls.parent(item.uri), top)
        }
        val folder = phonePath?.let { File(it).parentFile } ?: return null
        val top = listOf("/storage/emulated/0", "/sdcard").firstOrNull { folder.path.startsWith(it) }
            ?: folder.path.split('/').take(3).joinToString("/")          // a card: /storage/XXXX-XXXX
        return Triple(null, folder.path, top)
    }

    /** Folders, subtitle files and zip archives in [where] (sorted: folders, then files). */
    suspend fun browse(src: Source?, where: String): Result<List<BEntry>> = withContext(Dispatchers.IO) {
        try {
            val all = if (src != null) Browse.listAll(src, where, "subtitles").getOrThrow().map { BEntry(Uri.decode(it.name).trimEnd('/'), it.uri, if (it.isDir) 'd' else 'f') }
                else (File(where).listFiles() ?: throw IllegalStateException("can't read this folder")).map { BEntry(it.name, it.path, if (it.isDirectory) 'd' else 'f') }
            Result.success(all.mapNotNull { e -> when { e.kind == 'd' -> e; isSub(e.name) -> e.copy(kind = 's'); isZip(e.name) -> e.copy(kind = 'z'); else -> null } }
                .filter { !it.name.startsWith(".") }.sortedWith(compareBy({ it.kind != 'd' }, { it.name.lowercase() })))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** A subtitle file picked in the browser, copied into the app (phone files used where they are). */
    suspend fun fetch(ctx: Context, item: PlayItem, src: Source?, e: BEntry): File? = withContext(Dispatchers.IO) {
        if (src == null) return@withContext File(e.uri).takeIf { it.canRead() }
        val bytes = read(src, e.uri) ?: return@withContext null
        File(dir(ctx, "subtitles/" + hash(item.uri) + "/picked"), e.name.replace('/', '_')).apply { writeBytes(bytes) }
    }

    /** The subtitles inside a zip archive, unpacked into the app (up to 20 MB archives, 60 entries). */
    suspend fun unzip(ctx: Context, item: PlayItem, src: Source?, e: BEntry): List<File> = withContext(Dispatchers.IO) {
        val bytes = (if (src == null) File(e.uri).takeIf { it.length() <= 20_000_000 }?.readBytes() else read(src, e.uri)) ?: return@withContext emptyList()
        val out = ArrayList<File>()
        val d = dir(ctx, "subtitles/" + hash(item.uri) + "/zip-" + hash(e.uri))
        try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { z ->
                var en = z.nextEntry
                while (en != null && out.size < 60) {
                    val n = en.name.substringAfterLast('/')
                    if (!en.isDirectory && isSub(n)) { val b = z.readBytes(); if (b.size <= 2_000_000) out += File(d, n).apply { writeBytes(b) } }
                    en = z.nextEntry
                }
            }
        } catch (_: Exception) {}
        out
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
