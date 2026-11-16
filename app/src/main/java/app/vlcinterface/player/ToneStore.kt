package app.vlcinterface.player

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Loudness / treble per song (or per radio station), kept in the app only — the music files are never touched.
 * Condensed and fast: one 10-byte record per song (8-byte hash of its address + loudness + treble in 0.5 dB steps)
 * in tone.bin, all held in memory (instant look-up); a change is appended (10 bytes), and the file is compacted
 * at start when it holds many superseded records.
 */
object ToneStore {
    private lateinit var file: File
    private val map = HashMap<Long, Short>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tone.bin")
        var records = 0
        try {
            if (file.exists()) DataInputStream(file.inputStream().buffered()).use { inp ->
                while (true) { val h = try { inp.readLong() } catch (_: Exception) { break }; val v = inp.readShort(); map[h] = v; records++ }
            }
        } catch (_: Exception) {}
        if (records > map.size * 2 + 64) rewrite()
    }

    /** 64-bit FNV-1a of the song's key (address; CUE tracks also their start). */
    fun keyOf(item: PlayItem): Long {
        val k = item.uri.substringBefore('?') + if (item.startMs > 0) "#${item.startMs}" else ""
        var h = -0x340d631b7bdddcdbL
        for (c in k) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return h
    }

    /** (loudness dB, treble dB) saved for this song, or null. */
    fun get(item: PlayItem): Pair<Float, Float>? = synchronized(map) { map[keyOf(item)] }?.let { v ->
        (v.toInt() shr 8).toByte() / 2f to v.toByte() / 2f
    }

    fun put(item: PlayItem, loud: Float, treble: Float) {
        val h = keyOf(item)
        val v = (((Math.round(loud * 2).coerceIn(-127, 127) and 0xFF) shl 8) or (Math.round(treble * 2).coerceIn(-127, 127) and 0xFF)).toShort()
        synchronized(map) {
            if (map[h] == v) return
            map[h] = v
            try { DataOutputStream(FileOutputStream(file, true)).use { it.writeLong(h); it.writeShort(v.toInt()) } } catch (_: Exception) {}
        }
    }

    private fun rewrite() {
        try {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o -> synchronized(map) { map.forEach { (h, v) -> o.writeLong(h); o.writeShort(v.toInt()) } } }
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }
}
