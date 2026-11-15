package app.vlcinterface.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Music tags read during a manual Rescan, cached per file uri (+ size) so folder lists open instantly. */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val artwork: String = "",
    val size: Long = -1,
) {
    fun toJson(): JSONObject = JSONObject().put("t", title).put("a", artist).put("aa", albumArtist).put("al", album)
        .put("y", year).put("tr", track).put("d", disc).put("ms", durationMs).put("art", artwork).put("sz", size)

    companion object {
        fun fromJson(o: JSONObject) = Tags(o.optString("t"), o.optString("a"), o.optString("aa"), o.optString("al"),
            o.optInt("y"), o.optInt("tr"), o.optInt("d"), o.optLong("ms"), o.optString("art"), o.optLong("sz", -1))
    }
}

object TagCache {
    private lateinit var file: File
    private val map = HashMap<String, Tags>()
    @Volatile private var dirty = false

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tags.json")
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { k -> map[k] = Tags.fromJson(o.getJSONObject(k)) }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun get(uri: String): Tags? = map[uri]
    @Synchronized fun put(uri: String, t: Tags) { map[uri] = t; dirty = true }

    @Synchronized
    fun save() {
        if (!dirty || !::file.isInitialized) return
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v.toJson()) }
        file.writeText(o.toString())
        dirty = false
    }
}
