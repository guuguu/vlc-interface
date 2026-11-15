package app.vlcinterface.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import app.vlcinterface.App
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder

/**
 * Album pictures.
 *
 * 1. A picture already in the album folder (cover.jpg, folder.jpg, front.jpg, artist.jpg…).
 * 2. Otherwise, from the internet: the album picture (MusicBrainz + Cover Art Archive), or when the album
 *    has none, a photo of the artist / band (MusicBrainz → Wikimedia Commons / Wikidata). CDDB has no pictures.
 *
 * Downloaded pictures are resized for the phone (longest side 800 px, JPEG) and saved **only in the album
 * folder** (cover.jpg for the album picture, artist.jpg for the artist photo) — nowhere else. Phone folders need
 * the one-time folder access from Settings; read-only sources keep the picture only while the app runs.
 */
object CoverArt {
    private lateinit var miss: File        // "not found online" markers only (no pictures)
    private val lock = Mutex()
    private var lastCall = 0L
    private const val UA = "vlc-interface/0.3.0 ( https://github.com/guuguu/vlc-interface )"
    private const val MAX = 800
    private val names = listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "artist.jpg")
    // Pictures in memory, limited by size (not count): 24 MB at most — 24 pictures of 800 px could take ~60 MB.
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun init(ctx: Context) {
        miss = File(ctx.filesDir, "covers").apply { mkdirs() }
        // Pictures are no longer kept in the app (user rule): remove any stored by an earlier version.
        miss.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { it.delete() }
        // The search got better: forget every "not found online" from earlier versions once, so those albums are tried again.
        val v2 = File(miss, ".search-v2")
        if (!v2.exists()) { miss.listFiles { f -> f.name.endsWith(".none") }?.forEach { it.delete() }; v2.writeText("") }
    }

    /** In-memory picture for the notification / lock screen ("mem:" + key). */
    fun memory(key: String): Bitmap? = mem.get(key)

    data class Result(val bitmap: Bitmap?, val note: String)

    // ---------- where the album folder is ----------

    private sealed interface Place { val key: String }
    private data class Phone(val volume: String, val rel: String) : Place { override val key get() = "phone:$volume:$rel" }
    private data class Smb(val folder: String, val item: PlayItem) : Place { override val key get() = folder }
    private data class Web(val folder: String, val item: PlayItem, val writable: Boolean) : Place { override val key get() = folder }

    private fun place(ctx: Context, item: PlayItem): Place? {
        val u = item.uri
        return when {
            u.startsWith("content://") -> try {
                ctx.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && c.getString(0) != null) Phone(c.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY, c.getString(0)) else null
                }
            } catch (_: Exception) { null }
            u.startsWith("smb://") -> Smb(item.folderKey.takeIf { it.startsWith("smb://") }?.trimEnd('/')?.plus("/") ?: (u.substringBeforeLast('/') + "/"), item)
            u.startsWith("http") -> {
                val src = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }
                Web(item.folderKey.takeIf { it.startsWith("http") }?.trimEnd('/')?.plus("/") ?: (u.substringBeforeLast('/') + "/"), item, src?.proto == "webdav")
            }
            else -> null
        }
    }

    // Phone folders: through a folder the user allowed once (Settings → Album pictures → Allow phone folders).
    private fun phoneDoc(ctx: Context, p: Phone): Pair<Uri, String>? {
        val root = if (p.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY || p.volume == "external") "primary" else p.volume.uppercase()
        val folderId = "$root:" + p.rel.trimEnd('/')
        val tree = ctx.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri }.firstOrNull { t ->
            val tid = try { DocumentsContract.getTreeDocumentId(t) } catch (_: Exception) { "" }
            tid.isNotEmpty() && (folderId == tid || folderId.startsWith(tid.trimEnd('/') + "/") || tid.endsWith(":") && folderId.startsWith(tid))
        } ?: return null
        return tree to folderId
    }

    fun phoneAccessGranted(ctx: Context) = ctx.contentResolver.persistedUriPermissions.any { it.isWritePermission }

    // ---------- reading a picture from the folder ----------

    private fun readFolder(ctx: Context, pl: Place): ByteArray? = try {
        when (pl) {
            is Phone -> phoneDoc(ctx, pl)?.let { (tree, folderId) ->
                names.firstNotNullOfOrNull { n ->
                    try { ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "$folderId/$n"))?.use { it.readBytes() } } catch (_: Exception) { null }
                }
            }
            is Smb -> {
                val cx = SmbProbe.fileContext(pl.item.smbVersion, pl.item.user, pl.item.password, pl.item.domain)
                names.firstNotNullOfOrNull { n -> SmbFile(pl.folder + n, cx).takeIf { it.exists() }?.inputStream?.use { it.readBytes() } }
            }
            is Web -> names.firstNotNullOfOrNull { n ->
                Net.http.newCall(Request.Builder().url(pl.folder + n).apply { auth(this, pl.item) }.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }
        }
    } catch (_: Exception) { null }

    private fun auth(b: Request.Builder, item: PlayItem) { if (item.user.isNotEmpty()) b.header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }

    // ---------- saving into the folder ----------

    /** "" when saved, otherwise why not. */
    private fun saveFolder(ctx: Context, pl: Place, name: String, jpeg: ByteArray): String = try {
        when (pl) {
            is Phone -> {
                val (tree, folderId) = phoneDoc(ctx, pl) ?: return "not saved — allow phone folders in Settings → Library scanning"
                val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
                // Replacing a picture (chosen from the candidates): remove the old one first, or Android names the new one "cover (1).jpg".
                try { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, "$folderId/$name")) } catch (_: Exception) {}
                val out = DocumentsContract.createDocument(ctx.contentResolver, folderUri, "image/jpeg", name) ?: return "not saved — folder refused"
                ctx.contentResolver.openOutputStream(out, "wt")!!.use { it.write(jpeg) }; ""
            }
            is Smb -> {
                val cx = SmbProbe.fileContext(pl.item.smbVersion, pl.item.user, pl.item.password, pl.item.domain)
                SmbFile(pl.folder + name, cx).outputStream.use { it.write(jpeg) }; ""
            }
            is Web -> if (!pl.writable) "not saved — this source is read-only" else
                Net.http.newCall(Request.Builder().url(pl.folder + name).put(jpeg.toRequestBody("image/jpeg".toMediaType())).apply { auth(this, pl.item) }.build())
                    .execute().use { r -> if (r.isSuccessful) "" else "not saved — server refused (HTTP ${r.code})" }
        }
    } catch (e: Exception) { "not saved — ${e.message ?: e.javaClass.simpleName}" }

    /** Resized for the phone: longest side at most 800 px, JPEG quality 88. */
    private fun resize(bytes: ByteArray): Pair<Bitmap, ByteArray>? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val k = MAX.toFloat() / maxOf(src.width, src.height)
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return bmp to out.toByteArray()
    }

    // ---------- the lookups ----------

    /** Folder picture only (no internet): for Library album headers. */
    suspend fun folderOnly(ctx: Context, item: PlayItem): Bitmap? = withContext(Dispatchers.IO) {
        val pl = place(ctx, item) ?: return@withContext null
        mem.get(pl.key) ?: readFolder(ctx, pl)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }?.also { mem.put(pl.key, it) }
    }

    /** For the music player: folder picture, else internet (album, then artist), saved into the album folder. */
    suspend fun forPlayer(ctx: Context, item: PlayItem, artist: String, album: String, online: Boolean, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val pl = place(ctx, item)
        val memKey = pl?.key ?: item.uri
        if (!force) mem.get(memKey)?.let { mem.put(item.uri, it); return@withContext Result(it, "") }
        pl?.let { readFolder(ctx, it) }?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }?.let { mem.put(memKey, it); mem.put(item.uri, it); return@withContext Result(it, "") }
        if (!online) return@withContext Result(null, "")
        val (bytes, name) = albumPicture(artist, album, force)?.let { it to "cover.jpg" } ?: artistPicture(artist, force)?.let { it to "artist.jpg" }
            ?: return@withContext Result(null, if (force) "No picture found online for \"$album\"" + if (artist.isNotBlank()) " by $artist" else "" else "")
        val (bmp, jpeg) = resize(bytes) ?: return@withContext Result(null, "")
        mem.put(memKey, bmp); mem.put(item.uri, bmp)
        val why = if (pl == null) "not saved — no album folder for this source" else saveFolder(ctx, pl, name, jpeg)
        Result(bmp, if (why.isEmpty()) "${if (name == "artist.jpg") "Artist photo" else "Album picture"} saved in the album folder ($name)" else why)
    }

    private fun key(s: String) = s.trim().lowercase().hashCode().toUInt().toString(16)
    // "Not found online": asked again after 7 days (or at once with "Find picture again").
    private fun missed(k: String) = File(miss, "$k.none").let { it.exists() && System.currentTimeMillis() - it.lastModified() < 7L * 24 * 3600 * 1000 }
    private fun markMissed(k: String) { try { File(miss, "$k.none").writeText("") } catch (_: Exception) {} }

    private suspend fun mb(url: String): JSONObject? {
        val wait = 1100 - (System.currentTimeMillis() - lastCall); if (wait > 0) delay(wait)   // MusicBrainz: 1 request/s
        lastCall = System.currentTimeMillis()
        return Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    }

    private fun get(url: String): ByteArray? =
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }?.takeIf { it.size > 1000 }

    /** Album title variants: as tagged, without "(Remastered)" / "[Deluxe]" / "CD 1", without any bracketed extra, roman ↔ arabic numbers (ELO 2 ↔ ELO II). */
    private fun albumVariants(album: String): List<String> {
        val out = LinkedHashSet<String>()
        // Typographic apostrophes / accents used as apostrophes (´ ` ’ ‘ ʼ) never match MusicBrainz's plain ' .
        val a = album.trim().replace(Regex("[´`’‘ʼ′]"), "'"); out += a
        val clean = a.replace(Regex("""\s*[\(\[][^)\]]*(remaster|deluxe|edition|bonus|expanded|anniversary|mono|stereo|disc|cd)[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–:]?\s*(disc|cd)\s*\d+\s*$""", RegexOption.IGNORE_CASE), "").trim()
        if (clean.isNotEmpty()) out += clean
        // Anything else in brackets goes too: catalogue numbers "(C255-8001)", "[Japan]", "(1989)", label names…
        val bare = a.replace(Regex("""\s*[\(\[\{][^)\]\}]*[\)\]\}]"""), "").replace(Regex("""\s{2,}"""), " ").trim()
        if (bare.isNotEmpty()) out += bare
        val roman = mapOf("2" to "II", "3" to "III", "4" to "IV", "5" to "V", "6" to "VI", "7" to "VII", "8" to "VIII", "9" to "IX", "10" to "X")
        for (v in out.toList()) {
            roman.forEach { (d, r) ->
                if (Regex("""\b$d\b""").containsMatchIn(v)) out += v.replace(Regex("""\b$d\b"""), r)
                if (Regex("""\b$r\b""").containsMatchIn(v)) out += v.replace(Regex("""\b$r\b"""), d)
            }
        }
        return out.toList().take(5)
    }

    /** Does a MusicBrainz artist credit fit the tag? Same name, one inside the other, or initials (ELO = Electric Light Orchestra). */
    private fun artistFits(tag: String, credit: String): Boolean {
        if (tag.isBlank()) return true
        val t = tag.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim(); val c = credit.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        if (t.isEmpty() || c.isEmpty()) return true
        if (t == c || c.contains(t) || t.contains(c)) return true
        val initials = c.split(' ').filter { it.isNotEmpty() && it !in setOf("the", "and", "of") }.joinToString("") { it.take(1) }
        return t.replace(" ", "") == initials
    }

    /**
     * Album picture: several MusicBrainz searches until one fits — title variants, with the artist and then
     * without it (checking the artist afterwards, so "ELO" finds "Electric Light Orchestra"), release groups and
     * single releases. [force]: also when this album was marked "not found" before.
     */
    private suspend fun albumPicture(artist: String, album: String, force: Boolean = false): ByteArray? {
        if (album.isBlank()) return null
        val k = "al" + key("$artist|$album"); if (!force && missed(k)) return null
        return lock.withLock {
            try {
                fun q(s: String) = s.replace("\"", "")
                fun credit(o: JSONObject) = o.optJSONArray("artist-credit")?.let { c -> (0 until c.length()).joinToString(" ") { c.getJSONObject(it).optString("name") } } ?: ""
                val tried = HashSet<String>()
                for (title in albumVariants(album)) for (withArtist in listOf(true, false)) {
                    if (withArtist && artist.isBlank()) continue
                    for (kind in listOf("release-group", "release")) {
                        val field = if (kind == "release-group") "releasegroup" else "release"
                        val query = "$field:\"${q(title)}\"" + if (withArtist) " AND artist:\"${q(artist)}\"" else ""
                        if (!tried.add(query)) continue
                        val arr = mb("https://musicbrainz.org/ws/2/$kind/?fmt=json&limit=10&query=" + URLEncoder.encode(query, "UTF-8"))
                            ?.optJSONArray(if (kind == "release-group") "release-groups" else "releases") ?: continue
                        val ids = (0 until arr.length()).map { arr.getJSONObject(it) }
                            .filter { it.optInt("score") >= 85 && artistFits(artist, credit(it)) }.map { it.getString("id") }.take(4)
                        ids.firstNotNullOfOrNull { id -> get("https://coverartarchive.org/$kind/$id/front-500") }?.let { return@withLock it }
                    }
                }
                // Last try: loose word search (no exact phrase), punctuation dropped — "If You Want Blood, You've Got It"
                // still finds "If You Want Blood You've Got It". The artist must fit, and the score must be high.
                val base = album.trim().replace(Regex("[´`’‘ʼ′]"), "'").replace(Regex("""\s*[\(\[\{][^)\]\}]*[\)\]\}]"""), "").trim()
                val words = base.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isNotEmpty() && artist.isNotBlank()) for (kind in listOf("release-group", "release")) {
                    val field = if (kind == "release-group") "releasegroup" else "release"
                    val query = "$field:(${words.joinToString(" ") { w -> w.replace("'", "") }}) AND artist:\"${q(artist)}\""
                    val arr = mb("https://musicbrainz.org/ws/2/$kind/?fmt=json&limit=10&query=" + URLEncoder.encode(query, "UTF-8"))
                        ?.optJSONArray(if (kind == "release-group") "release-groups" else "releases") ?: continue
                    val want = norm(base)
                    val ids = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { it.optInt("score") >= 80 && artistFits(artist, credit(it)) && norm(it.optString("title")) == want }.map { it.getString("id") }.take(4)
                    ids.firstNotNullOfOrNull { id -> get("https://coverartarchive.org/$kind/$id/front-500") }?.let { return@withLock it }
                }
                markMissed(k); null
            } catch (_: Exception) { null }
        }
    }

    // ---------- choosing among several pictures ----------

    /** One album picture found online, to choose from (long-press on the player's picture). */
    data class Candidate(val kind: String, val id: String, val title: String, val credit: String, val info: String, val thumb: Bitmap)

    private fun creditOf(o: JSONObject) = o.optJSONArray("artist-credit")?.let { c -> (0 until c.length()).joinToString(" ") { c.getJSONObject(it).optString("name") } } ?: ""

    private fun infoOf(kind: String, o: JSONObject): String = if (kind == "release-group")
        listOf(o.optString("first-release-date").take(4), o.optString("primary-type"), "original cover").filter { it.isNotBlank() }.joinToString(" · ")
    else listOfNotNull(o.optString("date").take(4).ifBlank { null }, o.optString("country").ifBlank { null },
        o.optJSONArray("media")?.optJSONObject(0)?.optString("format")?.ifBlank { null },
        o.optJSONArray("label-info")?.optJSONObject(0)?.optJSONObject("label")?.optString("name")?.ifBlank { null }).joinToString(" · ")

    /**
     * Every album picture that fits this album and artist (different editions often have different covers),
     * with a small preview each. Same searches as the automatic one, but all fitting results are kept.
     */
    suspend fun candidates(artist: String, album: String): List<Candidate> = withContext(Dispatchers.IO) {
        if (album.isBlank()) return@withContext emptyList()
        val hits = LinkedHashMap<String, Triple<String, String, JSONObject>>()   // id → (kind, id, json)
        lock.withLock {
            try {
                fun q(t: String) = t.replace("\"", "")
                suspend fun search(query: String, kind: String, wantTitle: String?) {
                    val arr = mb("https://musicbrainz.org/ws/2/$kind/?fmt=json&limit=15&query=" + URLEncoder.encode(query, "UTF-8"))
                        ?.optJSONArray(if (kind == "release-group") "release-groups" else "releases") ?: return
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { it.optInt("score") >= 80 && artistFits(artist, creditOf(it)) && (wantTitle == null || norm(it.optString("title")) == wantTitle) }
                        .forEach { o -> hits.putIfAbsent(o.getString("id"), Triple(kind, o.getString("id"), o)) }
                }
                val base = album.trim().replace(Regex("[´`’‘ʼ′]"), "'").replace(Regex("""\s*[\(\[\{][^)\]\}]*[\)\]\}]"""), "").trim()
                for (withArtist in listOf(true, false)) {
                    if (withArtist && artist.isBlank()) continue
                    if (!withArtist && hits.isNotEmpty()) break
                    for (title in albumVariants(album)) {
                        for (kind in listOf("release-group", "release"))
                            search("${if (kind == "release-group") "releasegroup" else "release"}:\"${q(title)}\"" + if (withArtist) " AND artist:\"${q(artist)}\"" else "", kind, null)
                        if (hits.size >= 12) break
                    }
                }
                if (hits.isEmpty() && artist.isNotBlank()) {
                    val words = base.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { it.replace("'", "") }
                    if (words.isNotEmpty()) for (kind in listOf("release-group", "release"))
                        search("${if (kind == "release-group") "releasegroup" else "release"}:($words) AND artist:\"${q(artist)}\"", kind, norm(base))
                }
            } catch (_: Exception) {}
        }
        // Previews (Cover Art Archive, small size); entries without a picture drop out, identical pictures shown once.
        val seen = HashSet<Int>()
        hits.values.take(16).mapNotNull { (kind, id, o) ->
            val b = try { get("https://coverartarchive.org/$kind/$id/front-250") } catch (_: Exception) { null } ?: return@mapNotNull null
            if (!seen.add(b.contentHashCode())) return@mapNotNull null
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@mapNotNull null
            Candidate(kind, id, o.optString("title"), creditOf(o), infoOf(kind, o), bmp)
        }
    }

    /** The chosen picture: downloaded at full size, resized for the phone and saved as cover.jpg in the album folder. */
    suspend fun useCandidate(ctx: Context, item: PlayItem, c: Candidate): Result = withContext(Dispatchers.IO) {
        val bytes = try { get("https://coverartarchive.org/${c.kind}/${c.id}/front-1200") ?: get("https://coverartarchive.org/${c.kind}/${c.id}/front-500") } catch (_: Exception) { null }
            ?: return@withContext Result(null, "Couldn't download that picture")
        val (bmp, jpeg) = resize(bytes) ?: return@withContext Result(null, "Couldn't read that picture")
        val pl = place(ctx, item)
        mem.put(pl?.key ?: item.uri, bmp); mem.put(item.uri, bmp)
        val why = if (pl == null) "not saved — no album folder for this source" else saveFolder(ctx, pl, "cover.jpg", jpeg)
        Result(bmp, if (why.isEmpty()) "Album picture saved in the album folder (cover.jpg)" else why)
    }

    /** Title compared without case, accents-as-apostrophes and punctuation: "You´ve Got It," = "you've got it". */
    private fun norm(t: String) = t.lowercase().replace(Regex("[´`’‘ʼ′']"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun usableArtist(a: String) = a.isNotBlank() && a.trim().lowercase() !in setOf("various artists", "various", "va", "unknown", "<unknown>")

    private suspend fun artistPicture(artist: String, force: Boolean = false): ByteArray? {
        if (!usableArtist(artist)) return null
        val k = "ar" + key(artist); if (!force && missed(k)) return null
        return lock.withLock {
            try {
                val found = mb("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=3&query=" + URLEncoder.encode("artist:\"${artist.replace("\"", "")}\"", "UTF-8"))
                val id = found?.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.firstOrNull { it.optInt("score") >= 95 }?.getString("id")
                var file: String? = null
                if (id != null) {
                    val rels = mb("https://musicbrainz.org/ws/2/artist/$id?fmt=json&inc=url-rels")?.optJSONArray("relations")
                    val list = rels?.let { r -> (0 until r.length()).map { r.getJSONObject(it) } } ?: emptyList()
                    fun target(type: String) = list.firstOrNull { it.optString("type") == type }?.optJSONObject("url")?.optString("resource")
                    // Direct image link on Wikimedia Commons, else the Wikidata item's image (P18).
                    target("image")?.takeIf { "commons.wikimedia.org" in it }?.let { file = it.substringAfterLast("File:", "").ifEmpty { null } }
                    if (file == null) target("wikidata")?.substringAfterLast('/')?.let { q ->
                        val wd = Net.http.newCall(Request.Builder().url("https://www.wikidata.org/wiki/Special:EntityData/$q.json").header("User-Agent", UA).build())
                            .execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
                        file = wd?.optJSONObject("entities")?.optJSONObject(q)?.optJSONObject("claims")?.optJSONArray("P18")
                            ?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifEmpty { null }
                    }
                }
                file?.let { f ->
                    val name = java.net.URLDecoder.decode(f, "UTF-8").replace(' ', '_')
                    get("https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "?width=$MAX")
                } ?: null.also { markMissed(k) }
            } catch (_: Exception) { null }
        }
    }
}
