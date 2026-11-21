package app.vlcinterface.player

/**
 * Series episodes (2026-11-21 16:48): when a video whose name looks like an episode is played from a folder, the queue
 * is the whole succession of that series — the files of the folder (subfolders included) with the same series name —
 * in season / episode order, starting at the one tapped.
 *
 * Recognised: "S01E05" / "s1e5" / "S01.E05", "1x05", "Episode 5" / "Ep 5" / "Ep.05" / "E05", and a plain number after
 * the name ("Show - 05", "Show 05"). The series name is what comes before that, without separators, case and years.
 */
object Episodes {
    data class Ep(val series: String, val season: Int, val episode: Int)

    private val SE = Regex("""(?i)^(.*?)[\s._\-\[(]*s(\d{1,2})[\s._-]*e(\d{1,3})""")
    private val X = Regex("""(?i)^(.*?)[\s._\-\[(]*(\d{1,2})x(\d{1,3})(?!\d)""")
    private val EP = Regex("""(?i)^(.*?)[\s._\-\[(]*(?:episode|ep\.?|e)[\s._-]*(\d{1,3})(?!\d)""")
    private val NUM = Regex("""^(.*?\D)[\s._-]+(\d{1,3})(?:[\s._\-\])]|$)""")

    private fun base(name: String) = name.substringAfterLast('/').substringBeforeLast('.')

    private fun norm(s: String) = s.lowercase().replace(Regex("""[\s._\-\[\]()]+"""), " ").replace(Regex("""\b(19|20)\d{2}\b"""), "").trim()

    fun parse(name: String): Ep? {
        val b = base(name)
        SE.find(b)?.let { m -> return Ep(norm(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        X.find(b)?.let { m -> return Ep(norm(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        EP.find(b)?.let { m -> if (m.groupValues[1].isNotBlank()) return Ep(norm(m.groupValues[1]), 1, m.groupValues[2].toInt()) }
        NUM.find(b)?.let { m -> if (norm(m.groupValues[1]).length >= 2) return Ep(norm(m.groupValues[1]), 1, m.groupValues[2].toInt()) }
        return null
    }

    private fun nameOf(p: PlayItem) = p.fileName.ifEmpty { android.net.Uri.decode(p.uri.substringAfterLast('/')) }.ifEmpty { p.title }

    /** The tapped video's series from [items], in order; null when it isn't an episode or has no other episode there. */
    fun series(items: List<PlayItem>, tapped: PlayItem): List<PlayItem>? {
        val t = parse(nameOf(tapped)) ?: return null
        if (t.series.isEmpty()) return null
        val same = items.filter { !it.isMusic && !it.isRadio }.mapNotNull { p -> parse(nameOf(p))?.takeIf { it.series == t.series }?.let { p to it } }
        if (same.size < 2) return null
        return same.sortedWith(compareBy({ it.second.season }, { it.second.episode })).map { it.first }
    }
}
