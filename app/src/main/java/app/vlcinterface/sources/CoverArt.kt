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

    /**
     * Pictures for songs that are NOT in a folder holding one whole album (a folder mixing albums, loose files):
     * kept here as "Artist - Album.jpg" / "Artist.jpg" — the album folder can't hold them, one cover.jpg would be
     * wrong for the other albums there. Phone storage of the app, no permission needed.
     */
    lateinit var store: File
        private set

    fun init(ctx: Context) {
        store = (ctx.getExternalFilesDir("pictures") ?: File(ctx.filesDir, "pictures")).apply { mkdirs() }
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
            // jcifs needs the plain path (spaces etc. not %-encoded), like everywhere else in the app.
            u.startsWith("smb://") -> Smb(android.net.Uri.decode(item.folderKey.takeIf { it.startsWith("smb://") }?.trimEnd('/')?.plus("/") ?: (u.substringBeforeLast('/') + "/")), item)
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
                names.firstNotNullOfOrNull { n -> SmbFile(VpnLink.route(pl.folder + n), cx).takeIf { it.exists() }?.inputStream?.use { it.readBytes() } }
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
                SmbFile(VpnLink.route(pl.folder + name), cx).outputStream.use { it.write(jpeg) }; ""
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
        val pl = place(ctx, item)
        if (!inAlbumFolder(ctx, item, pl)) {
            val f = storeFind(item, item.artist, item.album) ?: return@withContext null
            return@withContext mem.get("store:" + item.uri) ?: BitmapFactory.decodeFile(f.path)?.also { mem.put("store:" + item.uri, it) }
        }
        mem.get(pl!!.key) ?: readFolder(ctx, pl)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }?.also { mem.put(pl.key, it) }
    }

    // ---------- one-album folder or not ----------

    private val wholeAlbum = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Does the song's folder hold one album only (all songs there share one album tag)? Remembered per folder. */
    private suspend fun inAlbumFolder(ctx: Context, item: PlayItem, pl: Place?): Boolean {
        if (pl == null) return false
        wholeAlbum[pl.key]?.let { return it }
        val songs = try { MusicLibrary.folderOf(ctx, item).filter { it.isMusic } } catch (_: Exception) { emptyList() }
        val albums = songs.map { it.album.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val one = songs.isNotEmpty() && albums.size <= 1
        wholeAlbum[pl.key] = one
        return one
    }

    // Pictures store: every picture is "<uuid>.jpg"; index.json says which song it was downloaded for
    // (song → uuid), and which album / artist it shows (so the other songs of that album use it too).
    private val songPic = HashMap<String, String>()
    private val keyPic = HashMap<String, String>()
    private var indexLoaded = false

    @Synchronized private fun loadIndex() {
        if (indexLoaded) return
        indexLoaded = true
        try {
            val f = File(store, "index.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.optJSONObject("songs")?.let { j -> j.keys().forEach { songPic[it] = j.getString(it) } }
                o.optJSONObject("albums")?.let { j -> j.keys().forEach { keyPic[it] = j.getString(it) } }
            }
        } catch (_: Exception) {}
    }

    @Synchronized private fun saveIndex() {
        try {
            File(store, "index.json").writeText(JSONObject().put("songs", JSONObject(songPic as Map<*, *>)).put("albums", JSONObject(keyPic as Map<*, *>)).toString())
        } catch (_: Exception) {}
    }

    private fun albumKey(artist: String, album: String) = "album:" + norm(artist) + "|" + norm(album)
    private fun artistKey(artist: String) = "artist:" + norm(artist)

    /** The stored picture for this song: its own, else its album's, else its artist's. */
    @Synchronized private fun storeFind(item: PlayItem, artist: String, album: String): File? {
        loadIndex()
        return listOfNotNull(songPic[item.uri], keyPic[albumKey(artist, album)], keyPic[artistKey(artist)])
            .map { File(store, "$it.jpg") }.firstOrNull { it.exists() }
    }

    /** Saves a picture under a new unique id, tied to the song it was downloaded for (and its album / artist). */
    @Synchronized private fun storeSave(item: PlayItem, artist: String, album: String, isArtist: Boolean, jpeg: ByteArray): File? {
        loadIndex()
        val id = java.util.UUID.randomUUID().toString()
        val f = File(store, "$id.jpg")
        return try {
            f.writeBytes(jpeg)
            songPic[item.uri] = id
            keyPic[if (isArtist) artistKey(artist) else albumKey(artist, album)] = id
            saveIndex(); f
        } catch (_: Exception) { null }
    }

    /** For the music player: picture from the album folder — or, for songs not in a one-album folder, from the
     *  pictures store — else from the internet (album, then artist), saved back to the same place. */
    /** A web picture (station logo): downloaded once into the temporary radio pictures folder; Wi-Fi only. */
    fun webPicture(ctx: Context, url: String): File? {
        val dir = File(ctx.cacheDir, "radio-pictures").apply { mkdirs() }
        val f = File(dir, "logo-" + key(url) + ".img")
        if (f.exists() && f.length() > 100) return f
        if (onMobile()) return null
        val b = try { Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } } catch (_: Exception) { null }
            ?: return null
        if (b.size < 100) return null
        f.writeBytes(b)
        return f
    }

    /** On mobile data (no Wi-Fi): no picture downloads. */
    fun onMobile() = app.vlcinterface.player.NetGuard.cellular.value

    /** "Beatles, The" → "The Beatles" (library-sorted names don't match online catalogues). */
    fun canonicalArtist(a: String): String {
        val m = Regex("""^(.+?),\s*(the|a|an|die|der|das|les|los|las|la|le|il|el)$""", RegexOption.IGNORE_CASE).find(a.trim()) ?: return a.trim()
        return m.groupValues[2].replaceFirstChar { it.uppercase() } + " " + m.groupValues[1]
    }

    suspend fun forPlayer(ctx: Context, item: PlayItem, artist0: String, album: String, online: Boolean, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val artist = canonicalArtist(artist0)
        val pl = place(ctx, item)
        val inFolder = inAlbumFolder(ctx, item, pl)
        val memKey = if (inFolder) pl!!.key else "store:" + item.uri
        if (!force) mem.get(memKey)?.let { mem.put(item.uri, it); return@withContext Result(it, "") }
        // A one-album folder on a read-only share keeps its picture in the pictures folder: looked for there too.
        val local = if (inFolder) readFolder(ctx, pl!!) ?: storeFind(item, artist, album)?.readBytes()
            else storeFind(item, artist, album)?.readBytes()
        local?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }?.let { mem.put(memKey, it); mem.put(item.uri, it); return@withContext Result(it, "") }
        if (!online) return@withContext Result(null, "")
        // Pictures are downloaded only over Wi-Fi (never on mobile data).
        if (onMobile()) return@withContext Result(null, if (force) "Pictures are downloaded only on Wi-Fi" else "")
        val (bytes, name) = albumPicture(artist, album, force)?.let { it to "cover.jpg" } ?: artistPicture(artist, force)?.let { it to "artist.jpg" }
            ?: return@withContext Result(null, if (force) "No picture found online for \"$album\"" + if (artist.isNotBlank()) " by $artist" else "" else "")
        val (bmp, jpeg) = resize(bytes) ?: return@withContext Result(null, "")
        mem.put(memKey, bmp); mem.put(item.uri, bmp)
        val isArtist = name == "artist.jpg"
        val what = if (isArtist) "Artist photo" else "Album picture"
        if (!inFolder) {
            val f = storeSave(item, artist, album, isArtist, jpeg)
            return@withContext Result(bmp, if (f != null) "$what saved in the pictures folder (${f.name}) — this folder mixes albums" else "not saved")
        }
        // Read-only share (marked, or refusing now): the picture goes to the app's pictures folder instead.
        val roItem = (pl as? Smb)?.item ?: (pl as? Web)?.item
        if (roItem != null && WriteGuard.isReadOnly(roItem)) {
            val f = storeSave(item, artist, album, isArtist, jpeg)
            return@withContext Result(bmp, if (f != null) "$what saved in the pictures folder (${f.name}) — the share is read-only" else "not saved")
        }
        val why = saveFolder(ctx, pl!!, name, jpeg)
        if (why.isNotEmpty() && roItem != null && why.contains("denied", true)) {
            WriteGuard.markReadOnly(roItem.sourceId, roItem.uri, why)
            val f = storeSave(item, artist, album, isArtist, jpeg)
            return@withContext Result(bmp, if (f != null) "$what saved in the pictures folder (${f.name}) — the share is read-only" else why)
        }
        Result(bmp, if (why.isEmpty()) "$what saved in the album folder ($name)" else why)
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
        // "beatles the" = "the beatles": same words, any order, articles ignored.
        fun words(x: String) = x.split(' ').filter { it.isNotEmpty() && it !in setOf("the", "a", "an") }.toSet()
        if (words(t).isNotEmpty() && words(t) == words(c)) return true
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
        val k = "al2" + key("$artist|$album"); if (!force && missed(k)) return null
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
                // Not in MusicBrainz / Cover Art Archive: the big shops' catalogues (iTunes, Deezer).
                webAlbums(artist, album).firstOrNull()?.let { (big, _) -> get(big)?.let { return@withLock it } }
                markMissed(k); null
            } catch (_: Exception) { null }
        }
    }

    private fun json(url: String): JSONObject? = try {
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    } catch (_: Exception) { null }

    /**
     * Album covers from iTunes and Deezer (no key needed): (big picture URL, small preview URL, title, artist).
     * Title must match one of the album's title variants, the artist must fit.
     */
    private fun webAlbumsFull(artist: String, album: String): List<List<String>> {
        if (album.isBlank()) return emptyList()
        val wants = albumVariants(album).map { norm(it) }.toSet()
        val out = ArrayList<List<String>>()
        fun fits(title: String, who: String) = norm(title).let { t -> wants.any { w -> t == w || t.startsWith("$w ") } } && artistFits(artist, who)
        for (title in albumVariants(album).reversed().take(3)) {   // plainest title first
            val term = URLEncoder.encode((if (usableArtist(artist)) "$artist " else "") + title, "UTF-8")
            json("https://itunes.apple.com/search?media=music&entity=album&limit=15&term=$term")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val art = o.optString("artworkUrl100"); if (art.isBlank()) continue
                    if (fits(o.optString("collectionName"), o.optString("artistName")))
                        out += listOf(art.replace("100x100bb", "1000x1000bb"), art.replace("100x100bb", "250x250bb"), o.optString("collectionName"), o.optString("artistName"), "iTunes")
                }
            }
            json("https://api.deezer.com/search/album?limit=15&q=$term")?.optJSONArray("data")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val big = o.optString("cover_xl"); if (big.isBlank() || "/cover//" in big) continue
                    val who = o.optJSONObject("artist")?.optString("name") ?: ""
                    if (fits(o.optString("title"), who)) out += listOf(big, o.optString("cover_medium").ifBlank { big }, o.optString("title"), who, "Deezer")
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.distinctBy { it[0] }
    }
    private fun webAlbums(artist: String, album: String) = webAlbumsFull(artist, album).map { it[0] to it[1] }

    /** Artist photo from Deezer (name must fit; Deezer's empty placeholder is skipped). */
    private fun deezerArtist(artist: String): Pair<String, String>? {
        if (!usableArtist(artist)) return null
        val a = json("https://api.deezer.com/search/artist?limit=5&q=" + URLEncoder.encode(artist, "UTF-8"))?.optJSONArray("data") ?: return null
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            val big = o.optString("picture_xl")
            if (big.isBlank() || "/artist//" in big) continue
            if (norm(o.optString("name")).let { n -> n == norm(artist) || artistFits(artist, o.optString("name")) && n.isNotEmpty() })
                return big to o.optString("picture_medium").ifBlank { big }
        }
        return null
    }

    /**
     * Song announced by a radio station → its album picture, else the artist's photo. Kept in a temporary folder
     * (app cache "radio-pictures", newest 60). Every attempt is written to the Radio pictures log, so a station whose
     * songs never get a picture can be looked at.
     *
     * The announced text is read in every usual form — "Artist - Title", "Title - Artist", "Artist – Title",
     * "Artist | Title", "Artist / Title", "Artist: Title", "Title by Artist", or no separator at all — cleaned of
     * extras ("(Live)", "[2011 Remaster]", "feat. …", years), then searched as a whole on iTunes and Deezer and the
     * best-matching result taken (words of artist and title compared, either order).
     */
    fun radioSong(ctx: Context, nowPlaying: String): File? {
        val np = nowPlaying.trim()
        if (np.length < 3) return null
        val dir = File(ctx.cacheDir, "radio-pictures").apply { mkdirs() }
        val k = key(np.lowercase())
        val f = File(dir, "$k.jpg")
        if (f.exists() && f.length() > 1000) { f.setLastModified(System.currentTimeMillis()); return f }
        if (missed("rs3$k")) return null
        if (onMobile()) return null                           // only on Wi-Fi
        fun clean(x: String) = x.replace(Regex("""\s*[\(\[][^\)\]]*[\)\]]"""), " ")
            .replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
            .replace(Regex("""\b(19|20)\d\d\b"""), " ").replace(Regex("\\s+"), " ").trim()
        // Possible (artist, title) readings of the text.
        val readings = ArrayList<Pair<String, String>>()
        val byM = Regex("""(?i)^(.+?)\s+by\s+(.+)$""").find(np)
        if (byM != null) readings += clean(byM.groupValues[2]) to clean(byM.groupValues[1])
        for (sep in listOf(" - ", " – ", " — ", " | ", " / ", " ~ ", ": ", " -", "- ")) {
            val i = np.indexOf(sep)
            if (i > 0) {
                val x = clean(np.substring(0, i)); val y = clean(np.substring(i + sep.length))
                if (x.isNotEmpty() && y.isNotEmpty()) { readings += x to y; readings += y to x }
                break
            }
        }
        val whole = clean(np.replace(Regex("""\s[-–—|/~:]\s"""), " "))
        fun words(x: String) = norm(x).split(' ').filter { it.length > 1 && it !in setOf("the", "a", "an", "and", "of", "la", "le", "el", "de") }.toSet()
        // How well a result (artist, title) fits the announced text: shared words, both parts needed.
        fun score(ra: String, rt: String): Double {
            val wa = words(ra); val wt = words(rt); val all = words(np)
            if (wa.isEmpty() || wt.isEmpty()) return 0.0
            val fa = wa.count { it in all }.toDouble() / wa.size
            val ft = wt.count { it in all }.toDouble() / wt.size
            return if (fa < 0.5 || ft < 0.5) 0.0 else (fa + ft) / 2
        }
        var best: Pair<Double, String>? = null
        fun consider(sc: Double, url: String?) { if (url != null && sc > (best?.first ?: 0.0)) best = sc to url }
        val terms = (listOf(whole) + readings.map { "${it.first} ${it.second}" }).map { it.trim() }.filter { it.length > 2 }.distinct().take(3)
        for (term in terms) {
            val q = URLEncoder.encode(term, "UTF-8")
            json("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=$q")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    consider(score(o.optString("artistName"), o.optString("trackName")),
                        o.optString("artworkUrl100").ifBlank { null }?.replace("100x100bb", "600x600bb")) }
            }
            json("https://api.deezer.com/search/track?limit=15&q=$q")?.optJSONArray("data")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    consider(score(o.optJSONObject("artist")?.optString("name") ?: "", o.optString("title")),
                        o.optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotBlank() && "/cover//" !in it }) }
            }
            if ((best?.first ?: 0.0) >= 0.99) break
        }
        var url = best?.takeIf { it.first >= 0.6 }?.second
        var what = if (url != null) "album picture (match ${"%.0f".format((best!!.first) * 100)} %)" else ""
        // No song found: the artist's photo (each reading's artist tried).
        if (url == null) for ((ar, _) in readings) {
            val ap = deezerArtist(ar)?.first
            if (ap != null) { url = ap; what = "artist photo of \"$ar\""; break }
        }
        val bytes = url?.let { try { get(it) } catch (_: Exception) { null } }
        val jpeg = bytes?.let { resize(it)?.second }
        if (jpeg == null) {
            markMissed("rs3$k")
            app.vlcinterface.data.ServerLog.log("radio-pictures", "picture", "web", "", false, "No picture for \"$np\"",
                "Searched: ${terms.joinToString(" | ")}\nBest match: ${best?.let { "%.0f %%".format(it.first * 100) } ?: "none"}")
            return null
        }
        f.writeBytes(jpeg)
        app.vlcinterface.data.ServerLog.log("radio-pictures", "picture", "web", "", true, "\"$np\": $what", url ?: "")
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        return f
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
    suspend fun candidates(artist0: String, album: String): List<Candidate> = withContext(Dispatchers.IO) {
        val artist = canonicalArtist(artist0)
        if (album.isBlank() && artist.isBlank()) return@withContext emptyList()
        if (onMobile()) return@withContext emptyList()       // only on Wi-Fi
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
        val mbList = hits.values.take(16).mapNotNull { (kind, id, o) ->
            val b = try { get("https://coverartarchive.org/$kind/$id/front-250") } catch (_: Exception) { null } ?: return@mapNotNull null
            if (!seen.add(b.contentHashCode())) return@mapNotNull null
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@mapNotNull null
            Candidate(kind, id, o.optString("title"), creditOf(o), infoOf(kind, o), bmp)
        }
        // Shops' catalogues too (iTunes, Deezer): kind "web", id = the full-size picture's address.
        val web = webAlbumsFull(artist, album).take(8).mapNotNull { (big, small, title, who, from) ->
            val b = get(small) ?: return@mapNotNull null
            if (!seen.add(b.contentHashCode())) return@mapNotNull null
            Candidate("web", big, title, who, from, BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@mapNotNull null)
        }
        val all = mbList + web
        if (all.isNotEmpty()) return@withContext all
        // No album cover anywhere: the artist's photo(s) instead.
        listOfNotNull(deezerArtist(artist)?.let { (big, small) ->
            get(small)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { Candidate("web", big, artist, artist, "Artist photo · Deezer", it) } }
        })
    }

    /** The chosen picture: downloaded at full size, resized for the phone and saved as cover.jpg in the album folder. */
    suspend fun useCandidate(ctx: Context, item: PlayItem, c: Candidate): Result = withContext(Dispatchers.IO) {
        val bytes = try {
            if (c.kind == "web") get(c.id)
            else get("https://coverartarchive.org/${c.kind}/${c.id}/front-1200") ?: get("https://coverartarchive.org/${c.kind}/${c.id}/front-500")
        } catch (_: Exception) { null }
            ?: return@withContext Result(null, "Couldn't download that picture")
        val (bmp, jpeg) = resize(bytes) ?: return@withContext Result(null, "Couldn't read that picture")
        val pl = place(ctx, item)
        if (!inAlbumFolder(ctx, item, pl)) {
            // Not a one-album folder: the chosen picture goes to the pictures store, named after the album.
            val f = storeSave(item, item.artist, item.album, false, jpeg)
            mem.put("store:" + item.uri, bmp); mem.put(item.uri, bmp)
            return@withContext Result(bmp, if (f != null) "Album picture saved in the pictures folder (${f.name})" else "not saved")
        }
        mem.put(pl!!.key, bmp); mem.put(item.uri, bmp)
        // Read-only share: into the app's pictures folder instead.
        val roItem = (pl as? Smb)?.item ?: (pl as? Web)?.item
        if (roItem != null && WriteGuard.isReadOnly(roItem)) {
            val f = storeSave(item, item.artist, item.album, false, jpeg)
            return@withContext Result(bmp, if (f != null) "Album picture saved in the pictures folder (${f.name}) — the share is read-only" else "not saved")
        }
        val why = saveFolder(ctx, pl, "cover.jpg", jpeg)
        if (why.isNotEmpty() && roItem != null && why.contains("denied", true)) {
            WriteGuard.markReadOnly(roItem.sourceId, roItem.uri, why)
            val f = storeSave(item, item.artist, item.album, false, jpeg)
            return@withContext Result(bmp, if (f != null) "Album picture saved in the pictures folder (${f.name}) — the share is read-only" else why)
        }
        Result(bmp, if (why.isEmpty()) "Album picture saved in the album folder (cover.jpg)" else why)
    }

    /** Title compared without case, accents-as-apostrophes and punctuation: "You´ve Got It," = "you've got it". */
    private fun norm(t: String) = t.lowercase().replace(Regex("[´`’‘ʼ′']"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun usableArtist(a: String) = a.isNotBlank() && a.trim().lowercase() !in setOf("various artists", "various", "va", "unknown", "<unknown>")

    private suspend fun artistPicture(artist: String, force: Boolean = false): ByteArray? {
        if (!usableArtist(artist)) return null
        val k = "ar2" + key(artist); if (!force && missed(k)) return null
        return lock.withLock {
            try {
                val found = mb("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=5&query=" + URLEncoder.encode("artist:\"${artist.replace("\"", "")}\"", "UTF-8"))
                val id = found?.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    ?.firstOrNull { it.optInt("score") >= 90 && artistFits(artist, it.optString("name")) }?.getString("id")
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
                } ?: deezerArtist(artist)?.let { get(it.first) } ?: null.also { markMissed(k) }
            } catch (_: Exception) { null }
        }
    }
}
