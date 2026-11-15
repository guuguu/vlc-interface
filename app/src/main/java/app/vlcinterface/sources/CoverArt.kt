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
    private val mem = LruCache<String, Bitmap>(24)

    fun init(ctx: Context) {
        miss = File(ctx.filesDir, "covers").apply { mkdirs() }
        // Pictures are no longer kept in the app (user rule): remove any stored by an earlier version.
        miss.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { it.delete() }
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
    suspend fun forPlayer(ctx: Context, item: PlayItem, artist: String, album: String, online: Boolean): Result = withContext(Dispatchers.IO) {
        val pl = place(ctx, item)
        val memKey = pl?.key ?: item.uri
        mem.get(memKey)?.let { mem.put(item.uri, it); return@withContext Result(it, "") }
        pl?.let { readFolder(ctx, it) }?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }?.let { mem.put(memKey, it); mem.put(item.uri, it); return@withContext Result(it, "") }
        if (!online) return@withContext Result(null, "")
        val (bytes, name) = albumPicture(artist, album)?.let { it to "cover.jpg" } ?: artistPicture(artist)?.let { it to "artist.jpg" } ?: return@withContext Result(null, "")
        val (bmp, jpeg) = resize(bytes) ?: return@withContext Result(null, "")
        mem.put(memKey, bmp); mem.put(item.uri, bmp)
        val why = if (pl == null) "not saved — no album folder for this source" else saveFolder(ctx, pl, name, jpeg)
        Result(bmp, if (why.isEmpty()) "${if (name == "artist.jpg") "Artist photo" else "Album picture"} saved in the album folder ($name)" else why)
    }

    private fun key(s: String) = s.trim().lowercase().hashCode().toUInt().toString(16)
    private fun missed(k: String) = File(miss, "$k.none").let { it.exists() && System.currentTimeMillis() - it.lastModified() < 30L * 24 * 3600 * 1000 }
    private fun markMissed(k: String) { try { File(miss, "$k.none").writeText("") } catch (_: Exception) {} }

    private suspend fun mb(url: String): JSONObject? {
        val wait = 1100 - (System.currentTimeMillis() - lastCall); if (wait > 0) delay(wait)   // MusicBrainz: 1 request/s
        lastCall = System.currentTimeMillis()
        return Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    }

    private fun get(url: String): ByteArray? =
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }?.takeIf { it.size > 1000 }

    private suspend fun albumPicture(artist: String, album: String): ByteArray? {
        if (album.isBlank()) return null
        val k = "al" + key("$artist|$album"); if (missed(k)) return null
        return lock.withLock {
            try {
                fun q(s: String) = s.replace("\"", "")
                val query = "releasegroup:\"${q(album)}\"" + if (artist.isNotBlank()) " AND artist:\"${q(artist)}\"" else ""
                val arr = mb("https://musicbrainz.org/ws/2/release-group/?fmt=json&limit=5&query=" + URLEncoder.encode(query, "UTF-8"))?.optJSONArray("release-groups")
                val ids = arr?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.filter { it.optInt("score") >= 90 }?.map { it.getString("id") } ?: emptyList()
                ids.firstNotNullOfOrNull { id -> get("https://coverartarchive.org/release-group/$id/front-500") } ?: null.also { markMissed(k) }
            } catch (_: Exception) { null }
        }
    }

    private fun usableArtist(a: String) = a.isNotBlank() && a.trim().lowercase() !in setOf("various artists", "various", "va", "unknown", "<unknown>")

    private suspend fun artistPicture(artist: String): ByteArray? {
        if (!usableArtist(artist)) return null
        val k = "ar" + key(artist); if (missed(k)) return null
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
