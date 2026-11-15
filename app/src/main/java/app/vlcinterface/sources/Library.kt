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
    /** Applies cached tags (from earlier Rescans) to network entries. */
    fun withCachedTags(list: List<Entry>): List<Entry> = list.map { e ->
        if (!e.isAudio || e.title.isNotEmpty()) e else TagCache.get(e.uri)?.let { t -> e.applyTags(t) } ?: e
    }

    private fun Entry.applyTags(t: Tags) = copy(
        title = t.title, artist = t.artist, albumArtist = t.albumArtist, album = t.album, year = t.year,
        track = t.track, disc = t.disc, durationMs = if (t.durationMs > 0) t.durationMs else durationMs, artwork = t.artwork,
    )

    /** Reads tags of the audio files in a listing with libVLC (only on a manual Rescan) and caches them. */
    suspend fun scanTags(src: Source, list: List<Entry>, force: Boolean): List<Entry> = withContext(Dispatchers.IO) {
        val player = App.instance.player
        player.setCredentials(src.user, src.password)
        val out = list.map { e ->
            if (!e.isAudio) return@map e
            val cached = TagCache.get(e.uri)
            if (cached != null && !force && (cached.size < 0 || cached.size == e.size)) return@map e.applyTags(cached)
            try {
                val url = if (src.smbVersion == "SMB1" && e.uri.startsWith("smb://"))
                    SmbBridge.urlFor(e.uri, "SMB1", src.user, src.password, src.domain) else e.uri
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

    fun toPlayItem(e: Entry, src: Source?, folderKey: String) = PlayItem(
        uri = e.uri, title = e.display, folderKey = folderKey, sourceId = src?.id ?: "",
        user = src?.user ?: "", password = src?.password ?: "", artist = e.artist, album = e.album,
        trackNo = e.track, durationMs = e.durationMs, artwork = e.artwork,
        smbVersion = src?.smbVersion ?: "", domain = src?.domain ?: "", audio = e.isAudio,
    )

    fun toPlEntry(p: PlayItem) = PlEntry(p.uri, p.title, p.sourceId, p.artist, p.album, p.durationMs, p.startMs, p.endMs, p.trackNo, p.folderKey)

    fun fromPlEntry(e: PlEntry): PlayItem {
        val src = App.instance.store.current.sources.firstOrNull { it.id == e.sourceId }
        return PlayItem(e.uri, e.title, folderKey = e.folderKey, sourceId = e.sourceId, user = src?.user ?: "", password = src?.password ?: "",
            artist = e.artist, album = e.album, trackNo = e.trackNo, durationMs = e.durationMs, startMs = e.startMs, endMs = e.endMs,
            smbVersion = src?.smbVersion ?: "", domain = src?.domain ?: "")
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
        return all.groupBy { it.meta }.toSortedMap().flatMap { (rel, l) ->
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
                "smb" -> SmbFile(Uri.decode(folderUrl.trimEnd('/') + "/" + name), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain)).exists()
                "webdav" -> {
                    val req = Request.Builder().url(folderUrl.trimEnd('/') + "/" + Uri.encode(name)).head()
                        .apply { if (src.user.isNotEmpty()) header("Authorization", Credentials.basic(src.user, src.password)) }.build()
                    Net.http.newCall(req).execute().use { it.isSuccessful }
                }
                else -> false
            }
        } catch (_: Exception) { false }
    }

    suspend fun write(src: Source, folderUrl: String, name: String, bytes: ByteArray, what: String): Result<String> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val target = folderUrl.trimEnd('/') + "/" + name
        val r: Result<String> = try {
            when (src.proto) {
                "smb" -> {
                    val f = SmbFile(Uri.decode(target), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain))
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
