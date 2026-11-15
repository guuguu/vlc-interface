package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CueTrack(val file: String, val number: Int, val title: String, val performer: String, val startMs: Long)

data class CueResult(val items: List<PlayItem>, val ignored: List<String>, val album: String)

/** .cue sheets: parse, check every referenced file really exists, ignore the ones that don't. */
object Cue {
    fun decode(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8).removePrefix("﻿")
        return if (s.contains('�')) String(b, charset("windows-1252")) else s
    }

    private fun unq(v: String) = v.trim().removeSurrounding("\"")

    fun parse(text: String): Pair<String, List<CueTrack>> {
        var album = ""; var albumPerformer = ""
        var file = ""; var num = 0; var title = ""; var perf = ""
        val out = ArrayList<CueTrack>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            val cmd = l.substringBefore(' ').uppercase()
            val arg = l.substringAfter(' ', "")
            when (cmd) {
                "FILE" -> file = unq(arg.substringBeforeLast(' ').ifEmpty { arg })
                "TRACK" -> { num = arg.trim().substringBefore(' ').toIntOrNull() ?: (num + 1); title = ""; perf = "" }
                "TITLE" -> if (num == 0) album = unq(arg) else title = unq(arg)
                "PERFORMER" -> if (num == 0) albumPerformer = unq(arg) else perf = unq(arg)
                "INDEX" -> {
                    val parts = arg.trim().split(Regex("\\s+"))
                    if (parts.getOrNull(0) == "01") {
                        val t = parts.getOrNull(1)?.split(':') ?: continue
                        val ms = (t.getOrNull(0)?.toLongOrNull() ?: 0) * 60000 + (t.getOrNull(1)?.toLongOrNull() ?: 0) * 1000 + (t.getOrNull(2)?.toLongOrNull() ?: 0) * 1000 / 75
                        out += CueTrack(file, num, title, perf.ifEmpty { albumPerformer }, ms)
                    }
                }
            }
        }
        return album to out
    }

    /** Turns tracks into playlist items. [resolve] maps a FILE entry to a real playable uri, or null when it doesn't exist. */
    private suspend fun build(album: String, tracks: List<CueTrack>, resolve: suspend (String) -> PlayItem?): CueResult {
        val resolved = HashMap<String, PlayItem?>()
        tracks.map { it.file }.distinct().forEach { f -> resolved[f] = resolve(f) }
        val ignored = resolved.filterValues { it == null }.keys.toList()
        val byFile = tracks.groupBy { it.file }
        val singleFileCut = byFile.values.any { it.size > 1 }
        val items = tracks.mapNotNull { t ->
            val base = resolved[t.file] ?: return@mapNotNull null
            val sameFile = byFile[t.file]!!
            val next = sameFile.firstOrNull { it.startMs > t.startMs }
            if (singleFileCut && sameFile.size > 1) base.copy(
                title = t.title.ifEmpty { "Track ${t.number}" }, artist = t.performer, album = album, trackNo = t.number,
                startMs = t.startMs, endMs = next?.startMs ?: -1, durationMs = if (next != null) next.startMs - t.startMs else 0,
            ) else base.copy(title = t.title.ifEmpty { base.title }, artist = t.performer.ifEmpty { base.artist }, album = album.ifEmpty { base.album }, trackNo = t.number)
        }
        return CueResult(items, ignored, album)
    }

    /** .cue on a saved network source: referenced files are looked up in the cue's folder (case-insensitive, Windows paths ok). */
    suspend fun fromSource(src: Source, cueUri: String, bytes: ByteArray): CueResult {
        val (album, tracks) = parse(decode(bytes))
        val folder = Urls.parent(cueUri)
        val listings = HashMap<String, List<Entry>>()
        return build(album, tracks) { file ->
            val rel = file.replace('\\', '/').trimStart('/')
            val sub = rel.substringBeforeLast('/', "")
            val name = rel.substringAfterLast('/')
            val dirUrl = if (sub.isEmpty()) folder else folder.trimEnd('/') + "/" + Urls.encodePath(sub) + "/"
            val list = listings.getOrPut(dirUrl) { Browse.listAll(src, dirUrl).getOrElse { emptyList() } }
            val hit = list.firstOrNull { !it.isDir && Uri.decode(it.name).equals(name, ignoreCase = true) }
            if (hit == null || !isPlayable(hit.name)) null
            else MusicLibrary.toPlayItem(hit, src, dirUrl)
        }
    }

    /** .cue picked from the phone: referenced files are looked up in Android's media index, same folder. */
    suspend fun fromPhone(ctx: Context, cueDoc: Uri): CueResult = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(cueDoc)?.use { it.readBytes() } ?: ByteArray(0)
        val (album, tracks) = parse(decode(bytes))
        // Document ids look like "primary:Music/Album/album.cue".
        val docId = try { DocumentsContract.getDocumentId(cueDoc) } catch (_: Exception) { "" }
        val relDir = docId.substringAfter(':', "").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        build(album, tracks) { file ->
            val rel = file.replace('\\', '/').trimStart('/')
            val dir = relDir + rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val name = rel.substringAfterLast('/')
            if (!isPlayable(name)) return@build null
            val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            ctx.contentResolver.query(coll, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(dir, name), null)?.use { c ->
                if (c.moveToFirst()) PlayItem(android.content.ContentUris.withAppendedId(coll, c.getLong(0)).toString(), name,
                    folderKey = "local:$dir", durationMs = c.getLong(2)) else null
            }
        }
    }
}
