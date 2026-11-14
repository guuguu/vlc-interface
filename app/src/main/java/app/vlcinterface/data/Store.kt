package app.vlcinterface.data

import android.content.Context
import app.vlcinterface.util.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Whole-app state in one JSON file. The same JSON (minus secrets handling) is the export format. */
class Store(private val context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state

    val current: AppState get() = _state.value

    @Synchronized
    fun update(block: (AppState) -> AppState) {
        _state.value = block(_state.value)
        val snapshot = _state.value
        scope.launch { save(snapshot) }
    }

    private fun load(): AppState = try {
        if (file.exists()) fromJson(JSONObject(file.readText()), Crypto::open) else AppState()
    } catch (e: Exception) {
        AppState()
    }

    @Synchronized
    private fun save(s: AppState) {
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(toJson(s, Crypto::seal).toString())
        tmp.renameTo(file)
    }

    // ---------- export / import ----------

    fun exportJson(includePasswords: Boolean, passphrase: String): String {
        val transform: (String) -> String = { p ->
            if (includePasswords && passphrase.isNotEmpty()) Crypto.sealWithPassphrase(p, passphrase) else ""
        }
        return JSONObject()
            .put("format", "vlc-interface-setup")
            .put("formatVersion", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("passwordsIncluded", includePasswords && passphrase.isNotEmpty())
            .put("state", toJson(current, transform))
            .toString(2)
    }

    data class ImportPreview(
        val ok: Boolean,
        val error: String = "",
        val sources: Int = 0,
        val stations: Int = 0,
        val needsPassphrase: Boolean = false,
        val raw: JSONObject? = null,
    )

    fun previewImport(text: String): ImportPreview = try {
        val root = JSONObject(text)
        if (root.optString("format") != "vlc-interface-setup") ImportPreview(false, "Not a vlc interface setup file")
        else {
            val st = root.getJSONObject("state")
            ImportPreview(
                true,
                sources = st.optJSONArray("sources")?.length() ?: 0,
                stations = st.optJSONArray("stations")?.length() ?: 0,
                needsPassphrase = root.optBoolean("passwordsIncluded"),
                raw = root,
            )
        }
    } catch (e: Exception) {
        ImportPreview(false, "Could not read the file: ${e.message}")
    }

    /** Returns an error message, or null on success. Backs up the current setup first. */
    fun applyImport(preview: ImportPreview, replace: Boolean, passphrase: String): String? {
        val root = preview.raw ?: return "Nothing to import"
        var wrongPass = false
        val transform: (String) -> String = { p ->
            if (p.isEmpty()) "" else Crypto.openWithPassphrase(p, passphrase) ?: run { wrongPass = true; "" }
        }
        val incoming = fromJson(root.getJSONObject("state"), transform)
        if (wrongPass) return "Wrong passphrase"
        backup()
        update { mine ->
            if (replace) incoming else mine.copy(
                sources = mine.sources + incoming.sources.filter { s -> mine.sources.none { it.id == s.id || (it.proto == s.proto && it.host == s.host && it.path == s.path) } },
                stations = mine.stations + incoming.stations.filter { s -> mine.stations.none { it.url == s.url } },
                subOffsets = incoming.subOffsets + mine.subOffsets,
                audioOffsets = incoming.audioOffsets + mine.audioOffsets,
                recent = (mine.recent + incoming.recent.filter { r -> mine.recent.none { it.uri == r.uri } }).take(50),
                scanned = incoming.scanned + mine.scanned,
            )
        }
        return null
    }

    fun backup(): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val name = "backup_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val f = File(dir, name)
        f.writeText(JSONObject().put("format", "vlc-interface-setup").put("formatVersion", FORMAT_VERSION)
            .put("passwordsIncluded", false).put("state", toJson(current) { "" }).toString(2))
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        return f
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun toJson(s: AppState, pw: (String) -> String): JSONObject = JSONObject()
            .put("settings", s.settings.toJson(pw))
            .put("sources", JSONArray(s.sources.map { it.toJson(pw) }))
            .put("stations", JSONArray(s.stations.map { it.toJson() }))
            .put("subStyle", s.subStyle.toJson())
            .put("subOffsets", s.subOffsets.toJson())
            .put("audioOffsets", s.audioOffsets.toJson())
            .put("recent", JSONArray(s.recent.map { it.toJson() }))
            .put("scanned", s.scanned.toJson())
            .put("lastOutput", s.lastOutput)

        fun fromJson(o: JSONObject, pw: (String) -> String): AppState {
            fun arr(name: String) = o.optJSONArray(name) ?: JSONArray()
            val sources = arr("sources").let { a -> (0 until a.length()).map { Source.fromJson(a.getJSONObject(it), pw) } }
            val stations = arr("stations").let { a -> (0 until a.length()).map { Station.fromJson(a.getJSONObject(it)) } }
            val recent = arr("recent").let { a -> (0 until a.length()).map { RecentItem.fromJson(a.getJSONObject(it)) } }
            return AppState(
                settings = Settings.fromJson(o.optJSONObject("settings"), pw),
                sources = sources,
                stations = stations,
                subStyle = SubStyle.fromJson(o.optJSONObject("subStyle")),
                subOffsets = o.optJSONObject("subOffsets").longMap(),
                audioOffsets = o.optJSONObject("audioOffsets").longMap(),
                recent = recent,
                scanned = o.optJSONObject("scanned").longMap(),
                lastOutput = o.optString("lastOutput"),
            )
        }
    }
}
