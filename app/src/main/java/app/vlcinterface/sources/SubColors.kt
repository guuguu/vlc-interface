package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Colour codes taken out of subtitle files (2026-11-21 16:09), so every subtitle is shown in the colours chosen in
 * Subs/Sync → Aa Style. Other formatting (italic, bold, positions, karaoke timing) is kept. The original file is never
 * changed: the player gets a cleaned copy in the app's cache.
 *
 * - SubRip (.srt) / WebVTT (.vtt): <font color=…> tags (the whole <font> tag: face / size go too) and VTT <c.colour>
 *   classes.
 * - MicroDVD (.sub): {c:$BBGGRR} / {C:$…} codes.
 * - SSA / ASS (.ssa / .ass): \c, \1c … \4c in the override blocks; the styles' own colours become the chosen text colour,
 *   border colour and a black shadow / box.
 */
object SubColors {
    private val FONT_TAG = Regex("""</?font\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val VTT_CLASS = Regex("""<c(\.[\w-]+)*>|</c>""", RegexOption.IGNORE_CASE)
    private val MICRODVD = Regex("""\{[cC]:\$[0-9A-Fa-f]{1,8}\}""")
    private val ASS_COLOUR = Regex("""\\[1-4]?c(&H[0-9A-Fa-f]+&?)?""", RegexOption.IGNORE_CASE)
    private val EMPTY_BLOCK = Regex("""\{\s*\}""")

    fun clean(text: String, ext: String, textRgb: Int = 0xFFFFFF, borderRgb: Int = 0x000000): String = when (ext.lowercase()) {
        "ass", "ssa" -> cleanAss(text, textRgb, borderRgb)
        "sub" -> text.replace(MICRODVD, "")
        else -> text.replace(FONT_TAG, "").replace(VTT_CLASS, "")
    }

    /** ASS colour: &HAABBGGRR (alpha 00 = solid). SSA v4 styles use the same value as a decimal number. */
    private fun assColour(rgb: Int, alpha: Int = 0, decimal: Boolean): String {
        val bgr = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
        return if (decimal) bgr.toString() else "&H%02X%06X".format(alpha, bgr)
    }

    private fun cleanAss(text: String, textRgb: Int, borderRgb: Int): String {
        val out = StringBuilder()
        var fields: List<String> = emptyList()
        var inStyles = false
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("[")) inStyles = t.contains("Styles", ignoreCase = true)
            when {
                inStyles && t.startsWith("Format:", ignoreCase = true) -> {
                    fields = t.substringAfter(':').split(',').map { it.trim().lowercase() }
                    out.append(line)
                }
                inStyles && t.startsWith("Style:", ignoreCase = true) && fields.isNotEmpty() -> {
                    val v = t.substringAfter(':').split(',').map { it.trim() }.toMutableList()
                    fun put(name: String, rgb: Int, alpha: Int) {
                        val i = fields.indexOf(name)
                        if (i in v.indices) v[i] = assColour(rgb, alpha, decimal = !v[i].startsWith("&H", ignoreCase = true))
                    }
                    put("primarycolour", textRgb, 0)
                    put("secondarycolour", textRgb, 0)
                    put("outlinecolour", borderRgb, 0)
                    put("tertiarycolour", borderRgb, 0)
                    put("backcolour", 0x000000, 0x80)
                    out.append("Style: ").append(v.joinToString(","))
                }
                t.startsWith("Dialogue:", ignoreCase = true) -> out.append(line.replace(ASS_COLOUR, "").replace(EMPTY_BLOCK, ""))
                else -> out.append(line)
            }
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * A cleaned copy of a subtitle file for the player (same file name in a folder of its own, so the track keeps its
     * name); null when it can't be read (the original is used then).
     */
    fun cleanedCopy(ctx: Context, uri: Uri, textRgb: Int, borderRgb: Int): Uri? = try {
        val name = (if (uri.scheme == "content") app.vlcinterface.sources.fileNameOf(ctx, uri.toString()) else uri.lastPathSegment).orEmpty()
            .ifEmpty { "subtitles.srt" }
        val ext = name.substringAfterLast('.', "srt").lowercase()
        if (ext !in setOf("srt", "vtt", "sub", "ass", "ssa")) null
        else {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("unreadable")
            val utf8 = String(bytes, Charsets.UTF_8)
            val cs = if (utf8.contains('�')) charset("windows-1252") else Charsets.UTF_8
            val cleaned = clean(String(bytes, cs), ext, textRgb, borderRgb)
            val key = MessageDigest.getInstance("SHA-1").digest(cleaned.toByteArray(cs) + uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val f = File(File(ctx.cacheDir, "subs-clean/$key").apply { mkdirs() }, name)
            f.writeBytes(cleaned.toByteArray(cs))
            Uri.fromFile(f)
        }
    } catch (_: Exception) { null }
}
