package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * SHOUTcast / Icecast links opened from other apps (browser, mail, chat):
 * .pls, .m3u, .m3u8 (radio), .xspf playlists, or a direct stream URL.
 * Playlists are read and the first stream inside is played as a radio station.
 */
object StreamLinks {
    private val PLAYLIST_EXT = setOf("pls", "m3u", "xspf")
    private val PLAYLIST_MIME = setOf(
        "audio/x-scpls", "audio/scpls", "application/pls+xml",
        "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl",
        "application/xspf+xml",
    )

    /** True when the opened link is a radio playlist or a SHOUTcast/Icecast stream. */
    fun isRadioLink(uri: Uri, mime: String?): Boolean {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        if (m != null && m in PLAYLIST_MIME) return true
        val ext = uri.lastPathSegment.orEmpty().ext()
        if (ext in PLAYLIST_EXT) return true
        val s = uri.scheme?.lowercase()
        // A plain http stream with no media file name (e.g. http://host:8000/ or /stream, /;) is a station.
        if ((s == "http" || s == "https" || s == "icy") && ext !in VIDEO_EXT && ext !in AUDIO_EXT && ext != "m3u8") return true
        // Dedicated stream links (08:24): mms / rtsp / rtmp without a media file name play as a station.
        if ((s == "mms" || s == "mmsh" || s == "rtsp" || s == "rtmp") && ext !in VIDEO_EXT) return true
        return false
    }

    data class Station(val name: String, val url: String)

    /** Reads the playlist (if it is one) and returns the stream to play with its title. */
    suspend fun resolve(ctx: Context, uri: Uri, mime: String?): Station = withContext(Dispatchers.IO) {
        val raw = uri.toString().let { if (it.startsWith("icy://")) "http://" + it.removePrefix("icy://") else it }
        val fallbackName = uri.host ?: uri.lastPathSegment ?: "Radio"
        val ext = uri.lastPathSegment.orEmpty().ext()
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        val looksPlaylist = ext in PLAYLIST_EXT || (m != null && m in PLAYLIST_MIME)
        val text = try {
            when (uri.scheme?.lowercase()) {
                "content", "file" -> ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
                else -> if (looksPlaylist) Net.http.newCall(Request.Builder().url(raw).header("User-Agent", Net.UA).build()).execute().use { r ->
                    r.body?.source()?.let { src -> src.request(64 * 1024); src.buffer.clone().readUtf8() }
                } else null
            }
        } catch (_: Exception) { null }
        val parsed = text?.let { parse(it) }
        if (parsed != null) Station(parsed.name.ifEmpty { fallbackName }, parsed.url)
        else Station(fallbackName, raw)
    }

    /** First stream of a .pls / .m3u / .xspf text, with its title when the playlist has one. */
    fun parse(text: String): Station? {
        val t = text.trimStart('﻿')
        if (t.contains("<playlist", ignoreCase = true) || t.contains("<location>", ignoreCase = true)) {
            val loc = Regex("<location>\\s*([^<]+?)\\s*</location>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1) ?: return null
            val title = Regex("<title>\\s*([^<]*?)\\s*</title>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1).orEmpty()
            return Station(title, loc)
        }
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("[playlist]", ignoreCase = true) || it.matches(Regex("(?i)File\\d+=.*")) }) {
            val file = lines.firstOrNull { it.matches(Regex("(?i)File1=.*")) } ?: lines.firstOrNull { it.matches(Regex("(?i)File\\d+=.*")) } ?: return null
            val n = file.substringBefore('=').drop(4)
            val title = lines.firstOrNull { it.startsWith("Title$n=", ignoreCase = true) }?.substringAfter('=').orEmpty()
            return Station(title, file.substringAfter('=').trim())
        }
        var title = ""
        for (l in lines) {
            if (l.startsWith("#EXTINF", ignoreCase = true)) title = l.substringAfter(',', "").trim()
            else if (!l.startsWith("#") && (l.contains("://"))) return Station(title, l)
        }
        return null
    }
}
