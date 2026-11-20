package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.PlEntry
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import app.vlcinterface.data.TagCache
import app.vlcinterface.data.Tags
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia

data class AlbumGroup(
    val album: String,
    val artist: String,
    val year: Int,
    val artwork: String,
    val entries: List<Entry>,
) {
    val totalMs: Long get() = entries.sumOf { it.durationMs }
    val multiDisc: Boolean get() = entries.map { it.disc }.distinct().size > 1
}

/** "05 - Song.mp3" → 5, "1-05 Song.flac" → 5, "Song.mp3" → 0 */
fun trackFromName(name: String): Int {
    val m = Regex("""^\s*(?:\d{1,2}[-.])?(\d{1,3})[\s._-]""").find(name) ?: return 0
    return m.groupValues[1].toIntOrNull() ?: 0
}

object MusicLibrary {
    /** Every audio / video file in the same folder as [item] (no subfolders), in album order. Empty when unknown. */
    suspend fun folderOf(ctx: Context, item: PlayItem): List<PlayItem> {
        // The song's folder, also when the song came from a playlist, the queue, the search or the index without a
        // folder / server noted (that used to give just the one song): worked out from its address then.
        val sources = App.instance.store.current.sources
        var fk = item.folderKey
        var src = sources.firstOrNull { it.id == item.sourceId }
        if (item.uri.contains("://") && !item.uri.startsWith("content://")) {
            if (src == null) {
                val host = item.uri.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
                src = sources.firstOrNull { it.host.substringAfter("://").substringBefore('/').substringBefore(':').equals(host, true) }
            }
            if (fk.isEmpty() || fk.startsWith("local:")) fk = Urls.parent(item.uri)
        } else if (!fk.startsWith("local:")) {
            // A phone song without its folder noted: take it from the phone's song list.
            fk = app.vlcinterface.ui.SongsCache.phoneFolderOf(ctx, item.uri) ?: fk
        }
        val r = folderOfKey(ctx, fk, src)
        if (r.size <= 1) app.vlcinterface.data.ServerLog.log(ServerLog.REPLAYGAIN, "folder", "", "", r.isNotEmpty(),
            "Folder of \"${item.title}\": ${r.size} song(s) found (folder ${fk.ifEmpty { "unknown" }}, server ${src?.name ?: src?.host ?: "none"})")
        return r
    }

    private suspend fun folderOfKey(ctx: Context, fk: String, src: Source?): List<PlayItem> {
        return try {
            when {
                fk.startsWith("local:") -> {
                    val vol = fk.removePrefix("local:").substringBefore(':'); val rel = fk.removePrefix("local:").substringAfter(':')
                    collectLocal(ctx, LocalFolder("$vol:$rel", rel, rel, 0, vol), false)
                }
                src != null && fk.isNotEmpty() -> Browse.list(src, fk).getOrNull()?.let { l ->
                    playOrder(l, fk.trimEnd('/').substringAfterLast('/')).map { toPlayItem(it, src, fk) }
                } ?: emptyList()
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Applies cached tags (from earlier Rescans) to network entries. */
    fun withCachedTags(list: List<Entry>): List<Entry> = list.map { e ->
        if (!e.isAudio || e.title.isNotEmpty()) e else TagCache.get(e.uri)?.let { t -> e.applyTags(t) } ?: e
    }

    private fun Entry.applyTags(t: Tags) = copy(
        title = t.title, artist = t.artist, albumArtist = t.albumArtist, album = t.album, year = t.year,
        track = t.track, disc = t.disc, durationMs = if (t.durationMs > 0) t.durationMs else durationMs, artwork = t.artwork,
    )

    /** Reads tags of the audio files in a listing with libVLC (only on a manual Rescan) and caches them. */
    /**
     * Reads the tags of the audio files in [list] (kept in the tag cache, so only new / changed files are read).
     * [sem]: when given, files are read in parallel, as many at once as it allows (LAN indexing uses 8).
     */
    suspend fun scanTags(src: Source, list: List<Entry>, force: Boolean, sem: kotlinx.coroutines.sync.Semaphore? = null): List<Entry> = withContext(Dispatchers.IO) {
        val player = App.instance.player
        player.setCredentials(src.user, src.password)
        fun tagOne(e: Entry): Entry {
            if (!e.isAudio) return e
            val cached = TagCache.get(e.uri)
            if (cached != null && !force && (cached.size < 0 || cached.size == e.size)) return e.applyTags(cached)
            return try {
                val url = if (src.smbVersion == "SMB1" && e.uri.startsWith("smb://"))
                    SmbBridge.urlFor(e.uri, "SMB1", src.user, src.password, src.domain)
                    else if (e.uri.startsWith("sftp://")) Sftp.urlFor(e.uri, src.user, src.password) else VpnLink.route(e.uri)
                val m = Media(player.libVlc, Uri.parse(url))
                m.parse(IMedia.Parse.ParseNetwork)
                fun meta(id: Int) = m.getMeta(id)?.trim().orEmpty()
                val name = e.name.substringBeforeLast('.')
                val t = Tags(
                    title = meta(IMedia.Meta.Title).let { if (it == name || it == e.name) "" else it },
                    artist = meta(IMedia.Meta.Artist),
                    albumArtist = meta(IMedia.Meta.AlbumArtist),
                    album = meta(IMedia.Meta.Album),
                    year = meta(IMedia.Meta.Date).take(4).toIntOrNull() ?: 0,
                    track = meta(IMedia.Meta.TrackNumber).substringBefore('/').toIntOrNull() ?: 0,
                    disc = meta(IMedia.Meta.DiscNumber).substringBefore('/').toIntOrNull() ?: 0,
                    durationMs = m.duration.coerceAtLeast(0),
                    artwork = meta(IMedia.Meta.ArtworkURL),
                    size = e.size,
                )
                m.release()
                TagCache.put(e.uri, t)
                e.applyTags(t)
            } catch (ex: Exception) {
                e
            }
        }
        val out = if (sem == null) list.map(::tagOne)
        else kotlinx.coroutines.coroutineScope {
            list.map { e -> async { if (!e.isAudio) e else sem.withPermit { tagOne(e) } } }.awaitAll()
        }
        TagCache.save()
        out
    }

    /**
     * Music grouped by album, songs in album order (disc → track), never alphabetical.
     * Untagged files: track from a leading number in the file name, grouped under the folder name.
     */
    fun group(list: List<Entry>, folderName: String): List<AlbumGroup> {
        val audio = list.filter { it.isAudio }.mapIndexed { i, e -> Triple(i, e, if (e.track > 0) e.track else trackFromName(e.name)) }
        if (audio.isEmpty()) return emptyList()
        val byAlbum = LinkedHashMap<String, MutableList<Triple<Int, Entry, Int>>>()
        audio.forEach { t ->
            val e = t.second
            val key = if (e.album.isNotEmpty()) (e.albumArtist.ifEmpty { e.artist }.lowercase() + "\u0000" + e.album.lowercase()) else "\u0001$folderName"
            byAlbum.getOrPut(key) { mutableListOf() } += t
        }
        val groups = byAlbum.map { (key, items) ->
            val sorted = items.sortedWith(compareBy<Triple<Int, Entry, Int>>(
                { it.second.disc },
                { if (it.third > 0) 0 else 1 },
                { if (it.third > 0) it.third else it.first },
            )).map { (_, e, tr) -> if (e.track == 0 && tr > 0) e.copy(track = tr) else e }
            val first = sorted.first()
            val untagged = key.startsWith("\u0001")
            AlbumGroup(
                album = if (untagged) folderName else first.album,
                artist = if (untagged) "" else first.albumArtist.ifEmpty { first.artist },
                year = sorted.maxOf { it.year },
                artwork = sorted.firstOrNull { it.artwork.isNotEmpty() }?.artwork ?: "",
                entries = sorted,
            )
        }
        return groups.sortedWith(compareBy<AlbumGroup>({ it.album == folderName && it.artist.isEmpty() }, { it.artist.lowercase() }, { if (it.year > 0) it.year else 9999 }))
    }

    /** Playback order for a folder: albums in display order, then videos in folder order. */
    fun playOrder(list: List<Entry>, folderName: String): List<Entry> =
        group(list, folderName).flatMap { it.entries } + list.filter { it.isVideo }

    // Items always keep the server's LAN address (a VPN address that slipped in is turned back): lists, playlists,
    // tags, ReplayGain, pictures stay the same at home and away; the VPN address is used only when opening.
    fun toPlayItem(e: Entry, src: Source?, folderKey: String) = PlayItem(
        uri = VpnLink.unroute(e.uri), title = e.display, folderKey = folderKey, sourceId = src?.id ?: "",
        user = src?.user ?: "", password = src?.password ?: "", artist = e.artist, album = e.album,
        trackNo = e.track, durationMs = e.durationMs, artwork = e.artwork,
        smbVersion = src?.smbVersion ?: "", domain = src?.domain ?: "", fileName = e.name,
    )

    const val RADIO_MARK = "radio"

    fun toPlEntry(p: PlayItem) = if (p.isRadio) PlEntry(p.uri, p.title, RADIO_MARK) else PlEntry(VpnLink.unroute(p.uri), p.title, p.sourceId, p.artist, p.album, p.durationMs, p.startMs, p.endMs, p.trackNo, VpnLink.unroute(p.folderKey), p.fileName)

    fun fromPlEntry(e: PlEntry): PlayItem {
        val src = App.instance.store.current.sources.firstOrNull { it.id == e.sourceId }
        // Radio stations kept in playlists (session playlists) are marked with sourceId "radio".
        if (e.sourceId == RADIO_MARK) return PlayItem(e.uri, e.title, isRadio = true)
        return PlayItem(e.uri, e.title, folderKey = e.folderKey, sourceId = e.sourceId, user = src?.user ?: "", password = src?.password ?: "",
            artist = e.artist, album = e.album, trackNo = e.trackNo, durationMs = e.durationMs, startMs = e.startMs, endMs = e.endMs,
            smbVersion = src?.smbVersion ?: "", domain = src?.domain ?: "", fileName = e.fileName.ifEmpty { fileNameOf(App.instance, e.uri) })
    }

    /**
     * Every audio/video file inside a folder and all its subfolders (parent first), in album order.
     * Playlists, images, .nfo, .cue, subtitles and anything else are skipped.
     */
    suspend fun collectRecursive(src: Source, uri: String, depth: Int = 0, onProgress: (Int) -> Unit = {}): List<PlayItem> {
        if (depth > 12) return emptyList()
        val list = withCachedTags(Browse.list(src, uri).getOrElse { return emptyList() })
        val here = playOrder(list, Urls.nameOf(uri)).map { toPlayItem(it, src, uri) }
        onProgress(here.size)
        val subs = list.filter { it.isDir }.flatMap { collectRecursive(src, it.uri, depth + 1, onProgress) }
        return here + subs
    }

    suspend fun collectLocal(ctx: Context, folder: LocalFolder, recursive: Boolean): List<PlayItem> {
        val all = InternalSource.files(ctx, folder, recursive)
        // Folder by folder in the order the Library shows them (by name, not case-sensitive), each in its displayed order.
        return all.groupBy { it.meta }.toSortedMap(compareBy<String> { it.lowercase() }.thenBy { it }).flatMap { (rel, l) ->
            playOrder(l, rel.trimEnd('/').substringAfterLast('/')).map { toPlayItem(it, null, "local:${folder.volume}:$rel") }
        }
    }
}

/** Writes small files (playlists, subtitles, logs) into a folder of a saved source. SMB (incl. SMB1) and WebDAV. */
object NasWriter {
    fun canWrite(src: Source) = src.proto == "smb" || src.proto == "webdav"

    suspend fun exists(src: Source, folderUrl: String, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            when (src.proto) {
                "smb" -> SmbFile(VpnLink.route(Uri.decode(folderUrl.trimEnd('/') + "/" + name)), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain)).exists()
                "webdav" -> {
                    val req = Request.Builder().url(folderUrl.trimEnd('/') + "/" + Uri.encode(name)).head()
                        .apply { if (src.user.isNotEmpty()) header("Authorization", Credentials.basic(src.user, src.password)) }.build()
                    Net.http.newCall(req).execute().use { it.isSuccessful }
                }
                else -> false
            }
        } catch (_: Exception) { false }
    }

    /** Deletes a file the app wrote earlier (e.g. a downloaded subtitle whose video is gone). */
    suspend fun delete(src: Source, folderUrl: String, name: String, what: String): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            when (src.proto) {
                "smb" -> SmbFile(VpnLink.route(Uri.decode(folderUrl.trimEnd('/') + "/" + name)), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain))
                    .let { if (it.exists()) { it.delete(); true } else true }
                "webdav" -> {
                    val req = Request.Builder().url(folderUrl.trimEnd('/') + "/" + Uri.encode(name)).delete()
                        .apply { if (src.user.isNotEmpty()) header("Authorization", Credentials.basic(src.user, src.password)) }.build()
                    Net.http.newCall(req).execute().use { it.isSuccessful || it.code == 404 }
                }
                else -> false
            }
        } catch (_: Exception) { false }
        ServerLog.log(src.host, "delete $what", src.proto.uppercase(), src.user, ok, if (ok) "Deleted $name" else "Could not delete $name")
        ok
    }

    suspend fun write(src: Source, folderUrl: String, name: String, bytes: ByteArray, what: String): Result<String> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val target = folderUrl.trimEnd('/') + "/" + name
        val r: Result<String> = try {
            when (src.proto) {
                "smb" -> {
                    val f = SmbFile(VpnLink.route(Uri.decode(target)), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain))
                    f.openOutputStream().use { it.write(bytes) }
                    Result.success(target)
                }
                "webdav" -> {
                    val req = Request.Builder().url(folderUrl.trimEnd('/') + "/" + Uri.encode(name))
                        .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                        .apply { if (src.user.isNotEmpty()) header("Authorization", Credentials.basic(src.user, src.password)) }.build()
                    Net.http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) Result.success(target) else Result.failure(Exception("Server answered ${resp.code}"))
                    }
                }
                else -> Result.failure(Exception("Writing isn't supported for ${src.proto.uppercase()} yet"))
            }
        } catch (e: Exception) {
            Result.failure(Exception(SmbErrors.explain(e)))
        }
        ServerLog.log(src.host, "write $what", src.proto.uppercase(), src.user, r.isSuccess,
            r.fold({ "Saved $name" }, { "Could not save $name: ${it.message}" }), startedAt = started)
        r
    }
}


/** File name (with extension) of any address; phone content:// addresses are looked up in Android's media index. */
fun fileNameOf(ctx: android.content.Context, uri: String): String {
    if (uri.startsWith("content://")) {
        try {
            ctx.contentResolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Exception) {
        }
        return ""
    }
    return android.net.Uri.decode(uri.substringBefore('?').trimEnd('/').substringAfterLast('/'))
}
