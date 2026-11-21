package app.vlcinterface.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.vlcinterface.player.PlayItem

/**
 * "Search on YouTube" for videos (requested 2026-11-21 21:04, old TV series): opens YouTube (the app when installed,
 * else the browser) with a search made from the video's name — the file name tidied up: separators to spaces,
 * release tags (720p, x264, WEB-DL, DVDRip …) and bracketed groups taken out, an episode code kept as "S02E05".
 */
object YouTube {
    private val TAGS = Regex(
        """(?i)\b(2160p|1080p|720p|576p|480p|360p|4k|uhd|hdr|x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|aac\d?(\.\d)?|ac3|dts|ddp?5\.1|5\.1|""" +
            """web[- ]?dl|webrip|web|hdtv|pdtv|dvdrip|dvd|bdrip|brrip|bluray|blu[- ]ray|remux|proper|repack|internal|limited|extended|""" +
            """multi|dual|subbed|dubbed|ita|eng|spa|esp|fre|ger|hd|sd|10bit|8bit)\b""")
    private val SE = Regex("""(?i)\bs(\d{1,2})[\s._-]*e(\d{1,3})\b""")
    private val X = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")

    /** The search words for this video. */
    fun query(item: PlayItem): String {
        val file = Uri.decode(item.uri).substringAfterLast('/').substringBeforeLast('.')
        // The tag title when it says more than the file name (e.g. a real episode title), else the file name.
        val t = item.title.trim()
        var s = if (t.isNotEmpty() && t != file && !t.contains('.') && t.length > 3) t else file
        s = s.replace(Regex("""\[[^\]]*]|\{[^}]*}"""), " ")                // [group] {tags}
            .replace(Regex("""\((?!(19|20)\d{2}\))[^)]*\)"""), " ")         // (…) except a year
            .replace(Regex("""[._]+"""), " ")
        // An episode code becomes S02E05 and what follows it (release tags) is dropped.
        SE.find(s)?.let { m -> s = s.substring(0, m.range.first) + "S%02dE%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            ?: X.find(s)?.let { m -> s = s.substring(0, m.range.first) + "S%02dE%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        s = s.replace(TAGS, " ").replace(Regex("""\s*-\s*$"""), "").replace(Regex("""\s+"""), " ").trim()
        return s.ifEmpty { file }
    }

    fun open(ctx: Context, item: PlayItem) {
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query(item))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            android.widget.Toast.makeText(ctx, "No app can open YouTube", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
