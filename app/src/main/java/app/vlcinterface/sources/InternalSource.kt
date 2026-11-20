package app.vlcinterface.sources

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class LocalFolder(val id: String, val name: String, val relativePath: String, val count: Int, val volume: String,
                       val videos: Int = 0, val songs: Int = 0)

/** Videos and music on the phone and SD card, through Android's media index. */
object InternalSource {
    private val collections = listOf(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
    )

    /** Folders on internal storage that are added to the Library without asking. */
    val USUAL = setOf("music", "movies", "podcasts", "audiobooks", "recordings", "dcim")

    /** "volume:Top/" of a folder: the place offered / approved as a whole. */
    fun rootKey(volume: String, relativePath: String) = "$volume:" + relativePath.substringBefore('/') + "/"

    fun isUsual(key: String) = key.substringBefore(':') == MediaStore.VOLUME_EXTERNAL_PRIMARY &&
        key.substringAfter(':').trimEnd('/').lowercase() in USUAL

    /** In the Library: always, unless media folders were chosen (first install / Find media folders) and this one wasn't. */
    fun approved(key: String): Boolean {
        val s = app.vlcinterface.App.instance.store.current.settings
        return !s.phoneFoldersSet || isUsual(key) || key in s.phoneFolders
    }

    class MediaRoot(val key: String, val label: String, val songs: Int, val videos: Int) {
        val usual get() = isUsual(key)
    }

    /** Every place on the phone holding songs or videos (approved or not), grouped by top folder; Downloads always listed. */
    suspend fun roots(ctx: Context): List<MediaRoot> {
        val all = folders(ctx, all = true)
        val by = all.groupBy { rootKey(it.volume, it.relativePath) }
        val list = by.map { (k, fs) ->
            val vol = k.substringBefore(':'); val top = k.substringAfter(':').trimEnd('/')
            val where = if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Internal" else if (vol.contains("usb", true)) "USB" else "SD card"
            MediaRoot(k, "$where · $top", fs.sumOf { it.songs }, fs.sumOf { it.videos })
        }.toMutableList()
        val dl = MediaStore.VOLUME_EXTERNAL_PRIMARY + ":" + Environment.DIRECTORY_DOWNLOADS + "/"
        if (list.none { it.key.equals(dl, true) }) list += MediaRoot(dl, "Internal · " + Environment.DIRECTORY_DOWNLOADS, 0, 0)
        return list.sortedWith(compareBy({ !it.usual }, { it.label.lowercase() }))
    }

    suspend fun folders(ctx: Context, all: Boolean = false): List<LocalFolder> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, LocalFolder>()
        for ((ci, coll) in collections.withIndex()) {
            val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
            try {
                ctx.contentResolver.query(coll, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val rel = c.getString(0) ?: continue
                        if (Ignore.dotPath(rel)) continue                      // hidden folders (".something")
                        val vol = c.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        val id = "$vol:$rel"
                        if (Ignore.blacklisted("local:$id")) continue          // folder taken out of the lists
                        if (!all && !approved(rootKey(vol, rel))) continue     // place not added to the Library
                        val prev = map[id]
                        val name = rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel } +
                            if (vol != MediaStore.VOLUME_EXTERNAL_PRIMARY) " (SD card)" else ""
                        map[id] = LocalFolder(id, name, rel, (prev?.count ?: 0) + 1, vol,
                            (prev?.videos ?: 0) + if (ci == 0) 1 else 0, (prev?.songs ?: 0) + if (ci == 1) 1 else 0)
                    }
                }
            } catch (_: SecurityException) {
            }
        }
        map.values.sortedBy { it.name.lowercase() }
    }

    /** [includeHidden]: also ".name" files / files in ".folders" (only for listing a folder before removing / deleting it). */
    suspend fun files(ctx: Context, folder: LocalFolder, recursive: Boolean = false, includeHidden: Boolean = false): List<Entry> = withContext(Dispatchers.IO) {
        val out = ArrayList<Entry>()
        val sel = if (recursive) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.VOLUME_NAME}=?"
            else "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.VOLUME_NAME}=?"
        val args = arrayOf(if (recursive) folder.relativePath + "%" else folder.relativePath, folder.volume)
        // Videos
        val vcoll = collections[0]
        try {
            ctx.contentResolver.query(vcoll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH), sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(vcoll, c.getLong(0))
                    out += Entry(c.getString(1) ?: "?", uri.toString(), false, c.getLong(2), meta = c.getString(4) ?: "", durationMs = c.getLong(3))
                }
            }
        } catch (_: SecurityException) {
        }
        // Music with tags
        val acoll = collections[1]
        try {
            ctx.contentResolver.query(acoll, arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.CD_TRACK_NUMBER, MediaStore.Audio.Media.DISC_NUMBER,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ), sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(acoll, c.getLong(0))
                    val name = c.getString(1) ?: "?"
                    val rawTrack = c.getInt(8)
                    val cdTrack = c.getString(10)?.substringBefore('/')?.trim()?.toIntOrNull()
                    val disc = c.getString(11)?.substringBefore('/')?.trim()?.toIntOrNull() ?: (if (rawTrack >= 1000) rawTrack / 1000 else 0)
                    val track = cdTrack ?: (rawTrack % 1000)
                    val title = c.getString(3).orEmpty().let { t -> if (t.isBlank() || t == name.substringBeforeLast('.')) "" else t }
                    fun clean(v: String?) = v.orEmpty().let { if (it == "<unknown>") "" else it }
                    out += Entry(name, uri.toString(), false, c.getLong(2), meta = c.getString(12) ?: "",
                        title = title, artist = clean(c.getString(4)), albumArtist = clean(c.getString(5)), album = clean(c.getString(6)),
                        year = c.getInt(7), track = track, disc = disc, durationMs = c.getLong(9), artwork = uri.toString())
                }
            }
        } catch (_: SecurityException) {
        }
        // Hidden (".name" files, files inside ".folders") and blacklisted files never come back.
        if (includeHidden) out.filter { isPlayable(it.name) }
        else out.filter { isPlayable(it.name) && !Ignore.dotName(it.name) && !Ignore.dotPath(it.meta) && !Ignore.blacklisted(it.uri) }
    }

    /** Asks Android to re-index one folder (manual rescan). */
    suspend fun rescan(ctx: Context, relativePath: String) {
        if (app.vlcinterface.player.BatteryGuard.busyBlocked()) return   // battery 15 % or less: only playing
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        val paths = (dir.listFiles()?.map { it.absolutePath } ?: emptyList()) + dir.absolutePath
        suspendCancellableCoroutine<Unit> { cont ->
            var left = paths.size
            if (left == 0) { cont.resume(Unit); return@suspendCancellableCoroutine }
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                left--
                if (left <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }
}
