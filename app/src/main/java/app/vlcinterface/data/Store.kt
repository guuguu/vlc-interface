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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state

    val current: AppState get() = _state.value

    private var pendingSave: kotlinx.coroutines.Job? = null

    /**
     * Changes apply at once; the file is written a moment later, once for a burst of changes (dragging a slider,
     * a scan saving many values…) instead of the whole state file being rewritten for each one.
     */
    @Synchronized
    fun update(block: (AppState) -> AppState) {
        _state.value = block(_state.value)
        pendingSave?.cancel()
        pendingSave = scope.launch { kotlinx.coroutines.delay(400); save(_state.value) }
    }

    /** Writes now (closing the app, going to the background). */
    fun flush() {
        synchronized(this) { pendingSave?.cancel(); pendingSave = null }
        try { save(_state.value) } catch (_: Exception) {}
    }

    private fun load(): AppState = try {
        if (file.exists()) fromJson(JSONObject(file.readText()), Crypto::open) else AppState()
    } catch (e: Exception) {
        // Unreadable state file: kept aside (state.json.bad-<time>) before the next save writes fresh defaults over it,
        // so libraries, playlists and settings can still be recovered from it.
        try { if (file.exists()) file.copyTo(File(file.parentFile, "state.json.bad-" + System.currentTimeMillis()), overwrite = true) } catch (_: Exception) {}
        try { ServerLog.log(CrashLog.KEY, "error", "app", "", false, "Settings file could not be read (${e.javaClass.simpleName}): kept aside as state.json.bad-…, defaults used") } catch (_: Exception) {}
        AppState()
    }

    @Synchronized
    private fun save(s: AppState) {
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(toJson(s, Crypto::seal).toString())
        tmp.renameTo(file)
    }

    // ---------- export / import ----------

    /** Whole setup (old call): everything, passwords included when a passphrase is given. */
    fun exportJson(includePasswords: Boolean, passphrase: String): String =
        exportJson(ExportCat.ALL_IDS.toSet().let { if (includePasswords) it else it - ExportCat.PASSWORDS }, passphrase)

    /** Only the chosen categories ([ExportCat]); passwords only with the PASSWORDS category and a passphrase. */
    fun exportJson(cats: Set<String>, passphrase: String): String {
        val c = if (ExportCat.ALL in cats) ExportCat.ALL_IDS.toSet() else cats
        val withPw = ExportCat.PASSWORDS in c && passphrase.isNotEmpty()
        val transform: (String) -> String = { p -> if (withPw && p.isNotEmpty()) Crypto.sealWithPassphrase(p, passphrase) else "" }
        val full = toJson(current, transform)
        val st = JSONObject()
        if (ExportCat.SETTINGS in c || ExportCat.VPN in c) {
            val all = full.getJSONObject("settings"); val o = JSONObject()
            all.keys().forEach { k ->
                val vpn = k in ExportCat.VPN_KEYS
                if ((vpn && ExportCat.VPN in c) || (!vpn && ExportCat.SETTINGS in c)) o.put(k, all.get(k))
            }
            st.put("settings", o)
        }
        if (ExportCat.SETTINGS in c) listOf("lastOutput", "devicePreamp", "deviceBalance", "deviceLoudFrom").forEach { st.put(it, full.get(it)) }
        val zones = buildSet { if (ExportCat.NAS in c) add(Zone.LAN); if (ExportCat.INTERNET in c) add(Zone.INTERNET) }
        if (zones.isNotEmpty()) st.put("sources", JSONArray(current.sources.filter { it.zone in zones }.map { it.toJson(transform) }))
        if (ExportCat.RADIO in c) st.put("stations", full.get("stations"))
        if (ExportCat.PLAYLISTS in c) st.put("playlists", full.get("playlists"))
        if (ExportCat.SUBS in c) listOf("subStyle", "subOffsets", "audioOffsets").forEach { st.put(it, full.get(it)) }
        if (ExportCat.RESUME in c) listOf("recent", "scanned").forEach { st.put(it, full.get(it)) }
        if (ExportCat.ALARMS in c) try { st.put("alarms", app.vlcinterface.alarm.Alarms.exportJson()) } catch (_: Exception) {}
        if (ExportCat.VIS in c) try { st.put("vis", app.vlcinterface.alarm.Alarms.exportVis()) } catch (_: Exception) {}
        // Everything kept outside the main settings file (2026-11-22 08:32): each part goes with its category.
        try { st.put("extras", ExportExtras.export(context, c)) } catch (_: Exception) {}
        return JSONObject()
            .put("format", "vlc-interface-setup")
            .put("formatVersion", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("passwordsIncluded", withPw)
            .put("categories", JSONArray((c - ExportCat.ALL - (if (withPw) emptySet() else setOf(ExportCat.PASSWORDS))).toList()))
            .put("state", st)
            .toString(2)
    }

    data class ImportPreview(
        val ok: Boolean,
        val error: String = "",
        val sources: Int = 0,
        val stations: Int = 0,
        val needsPassphrase: Boolean = false,
        val raw: JSONObject? = null,
        /** Categories found in the file. */
        val cats: Set<String> = emptySet(),
    )

    fun previewImport(text: String): ImportPreview = try {
        val root = JSONObject(text)
        if (root.optString("format") != "vlc-interface-setup") ImportPreview(false, "Not a vlc interface setup file")
        else {
            val st = root.getJSONObject("state")
            val pw = root.optBoolean("passwordsIncluded")
            val cats = root.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: (ExportCat.ALL_IDS.toSet() - ExportCat.ALARMS - (if (pw) emptySet() else setOf(ExportCat.PASSWORDS)))   // older files
            // Files from before the split carry the visualisation inside the alarms section.
            val catsV = cats.let { if (ExportCat.ALARMS in it && ExportCat.VIS !in it && st.optJSONObject("alarms")?.has("vis") == true) it + ExportCat.VIS else it }
                .filter { it in ExportCat.ALL_IDS }.toSet()
            ImportPreview(
                true,
                sources = st.optJSONArray("sources")?.length() ?: 0,
                stations = st.optJSONArray("stations")?.length() ?: 0,
                needsPassphrase = pw,
                raw = root,
                cats = catsV,
            )
        }
    } catch (e: Exception) {
        ImportPreview(false, "Could not read the file: ${e.message}")
    }

    /** Old call: every category in the file. */
    fun applyImport(preview: ImportPreview, replace: Boolean, passphrase: String): String? = applyImport(preview, replace, passphrase, preview.cats)

    /**
     * Applies only the chosen categories [cats] (the rest of the setup stays as it is). Replace: those parts are
     * replaced; merge: new items are added, mine kept. Ticked settings are applied in both modes.
     * Returns an error message, or null on success. Backs up the current setup first.
     */
    fun applyImport(preview: ImportPreview, replace: Boolean, passphrase: String, cats: Set<String>): String? {
        val root = preview.raw ?: return "Nothing to import"
        val c = cats.intersect(preview.cats)
        val usePw = ExportCat.PASSWORDS in c && preview.needsPassphrase
        var wrongPass = false
        val transform: (String) -> String = { p ->
            if (p.isEmpty() || !usePw) "" else Crypto.openWithPassphrase(p, passphrase) ?: run { wrongPass = true; "" }
        }
        val stJ = root.getJSONObject("state")
        val incoming = fromJson(stJ, transform)
        if (wrongPass) return "Wrong passphrase"
        backup()
        update { mine ->
            var m = mine
            val inS = stJ.optJSONObject("settings")
            if (inS != null && (ExportCat.SETTINGS in c || ExportCat.VPN in c)) {
                val myS = mine.settings.toJson { it }
                inS.keys().forEach { k ->
                    val vpn = k in ExportCat.VPN_KEYS
                    if ((vpn && ExportCat.VPN in c) || (!vpn && ExportCat.SETTINGS in c)) myS.put(k, inS.get(k))
                }
                m = m.copy(settings = Settings.fromJson(myS) { it })
            }
            if (ExportCat.SETTINGS in c && stJ.has("devicePreamp")) m = m.copy(
                devicePreamp = if (replace) incoming.devicePreamp else incoming.devicePreamp + mine.devicePreamp,
                deviceBalance = if (replace) incoming.deviceBalance else incoming.deviceBalance + mine.deviceBalance,
                deviceLoudFrom = if (replace) incoming.deviceLoudFrom else incoming.deviceLoudFrom + mine.deviceLoudFrom,
            )
            val zones = buildSet { if (ExportCat.NAS in c) add(Zone.LAN); if (ExportCat.INTERNET in c) add(Zone.INTERNET) }
            if (zones.isNotEmpty() && stJ.has("sources")) {
                fun same(a: Source, b: Source) = a.id == b.id || (a.proto == b.proto && a.host == b.host && a.path == b.path)
                // No password in the file (or not imported): the one already saved for that server stays.
                val inc = incoming.sources.filter { it.zone in zones }.map { s ->
                    if (s.password.isNotEmpty()) s else mine.sources.firstOrNull { same(it, s) }?.let { s.copy(password = it.password) } ?: s }
                m = m.copy(sources = if (replace) mine.sources.filter { it.zone !in zones } + inc
                    else mine.sources + inc.filter { s -> mine.sources.none { same(it, s) } })
            }
            if (ExportCat.RADIO in c && stJ.has("stations")) m = m.copy(stations = if (replace) incoming.stations
                else mine.stations + incoming.stations.filter { s -> mine.stations.none { it.url == s.url } })
            if (ExportCat.PLAYLISTS in c && stJ.has("playlists")) m = m.copy(playlists = if (replace) incoming.playlists
                else mine.playlists + incoming.playlists.filter { p -> mine.playlists.none { it.id == p.id || it.name == p.name } })
            if (ExportCat.SUBS in c && stJ.has("subStyle")) m = m.copy(subStyle = incoming.subStyle,
                subOffsets = if (replace) incoming.subOffsets else incoming.subOffsets + mine.subOffsets,
                audioOffsets = if (replace) incoming.audioOffsets else incoming.audioOffsets + mine.audioOffsets)
            if (ExportCat.RESUME in c && stJ.has("recent")) m = m.copy(
                recent = if (replace) incoming.recent else (mine.recent + incoming.recent.filter { r -> mine.recent.none { it.uri == r.uri } }).take(50),
                scanned = if (replace) incoming.scanned else incoming.scanned + mine.scanned)
            m
        }
        if (ExportCat.ALARMS in c) stJ.optJSONObject("alarms")?.let { try { app.vlcinterface.alarm.Alarms.importJson(it, replace) } catch (_: Exception) {} }
        // Visualisation: its own section; older files kept it inside the alarms section.
        if (ExportCat.VIS in c) (stJ.optJSONObject("vis") ?: stJ.optJSONObject("alarms"))?.let { try { app.vlcinterface.alarm.Alarms.importVis(it) } catch (_: Exception) {} }
        // Parts kept outside the settings file: written back; some take effect after the app restarts.
        lastImportNeedsRestart = try { stJ.optJSONObject("extras")?.let { ExportExtras.import(context, it, c, replace) } ?: false } catch (_: Exception) { false }
        return null
    }

    /** The last import wrote parts that are read when the app starts (it offers a restart). */
    var lastImportNeedsRestart = false

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
        /** 2 (2026-11-22): + "extras" (per-video choices, ReplayGain kept in the app, file associations, …). */
        const val FORMAT_VERSION = 2

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
            .put("playlists", JSONArray(s.playlists.map { it.toJson() }))
            .put("devicePreamp", JSONObject().also { o -> s.devicePreamp.forEach { (k, v) -> o.put(k, v.toDouble()) } })
            .put("deviceBalance", JSONObject().also { o -> s.deviceBalance.forEach { (k, v) -> o.put(k, v) } })
            .put("deviceLoudFrom", JSONObject().also { o -> s.deviceLoudFrom.forEach { (k, v) -> o.put(k, v) } })

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
                playlists = arr("playlists").let { a -> (0 until a.length()).map { Playlist.fromJson(a.getJSONObject(it)) } },
                devicePreamp = o.optJSONObject("devicePreamp").let { d -> if (d == null) emptyMap() else d.keys().asSequence().associateWith { d.optDouble(it, 0.0).toFloat() } },
                deviceBalance = o.optJSONObject("deviceBalance").let { d -> if (d == null) emptyMap() else d.keys().asSequence().associateWith { d.optInt(it, 0) } },
                deviceLoudFrom = o.optJSONObject("deviceLoudFrom").let { d -> if (d == null) emptyMap() else d.keys().asSequence().associateWith { d.optInt(it, 50).coerceIn(0, 100) } },
            )
        }
    }
}


/** Categories of the setup file (Export / import). */
object ExportCat {
    const val ALL = "all"
    const val SETTINGS = "settings"
    const val NAS = "nas"
    const val VPN = "vpn"
    const val INTERNET = "internet"
    const val PASSWORDS = "passwords"
    const val RADIO = "radio"
    const val PLAYLISTS = "playlists"
    const val ALARMS = "alarms"
    const val SUBS = "subs"
    const val RESUME = "resume"
    const val VIS = "vis"
    const val VIDEOS = "videos"
    const val RG = "rg"
    const val ASSOC = "assoc"
    const val INDEX = "index"
    const val TONE = "tone"
    /** id → name, in the order shown. */
    val LIST = listOf(SETTINGS to "Settings", NAS to "NAS (servers, shares, addresses)", VPN to "VPN (app, home servers, retry)",
        INTERNET to "Internet sources", PASSWORDS to "Saved passwords", RADIO to "Radio favourites", PLAYLISTS to "Playlists",
        ALARMS to "Alarms and clock", VIS to "Visualisation (all patterns' settings)", SUBS to "Subtitle style and sync offsets", RESUME to "Resume positions and scanned folders",
        VIDEOS to "Per-video choices (format, decoder, tracks, colours, audio sync)", RG to "ReplayGain values kept in the app",
        ASSOC to "File associations", INDEX to "Indexes of the network locations (search)",
        TONE to "Loudness, Treble and Preamp per song and station")
    val ALL_IDS = LIST.map { it.first }
    /** Settings that belong to the VPN category. */
    val VPN_KEYS = setOf("vpnNas", "vpnAskStart", "vpnApp", "vpnRetrySec")
}


/**
 * Everything the app keeps outside the main settings file, for export / import (2026-11-22 08:32), each with the
 * category it belongs to. Device-only data (caches, logs, the index, battery and crash notes…) is listed in
 * [LOCAL_PREFS] / [LOCAL_FILES] on purpose. The build checks that every stored preference file and data file is in
 * one of these lists (tools/check_export.py), so nothing new is ever left out of the export by accident.
 */
object ExportExtras {
    /** Preference files → category. */
    val PREFS = mapOf(
        "uiChoices" to ExportCat.SETTINGS,            // choices left on the pages (Songs / Radio / All …)
        "settings_search" to ExportCat.SETTINGS,      // the Settings search history
        "music_folder_access" to ExportCat.SETTINGS,  // folders answered "No" for ReplayGain write access
        "playerSwitch" to ExportCat.RESUME,           // what each player played last
        "videoFiles" to ExportCat.VIDEOS,             // per video: format, decoder, deinterlacing, tracks, colours, audio sync
        "rgScanDates2" to ExportCat.RG,               // when each network song was measured, and where its values went
        "netindex_at" to ExportCat.INDEX,
        "lan_seen" to ExportCat.NAS,
        "short_alarms" to ExportCat.ALARMS,           // the short notification alarms (2026-11-28 01:17)                  // servers once found on the LAN, with their addresses (01:13)             // when each indexed folder was indexed, and what its index holds (2026-11-28 01:12)
    )
    /** Data files in the app's storage → category. */
    val FILES = mapOf(
        "vpn-extra-subnets.txt" to ExportCat.VPN,
        "vpn-ignored-servers.txt" to ExportCat.VPN,
        "covers-banned.json" to ExportCat.SETTINGS,   // album pictures you rejected
        "saved-songs.json" to ExportCat.RADIO,        // songs saved from the radio
        "replaygain.json" to ExportCat.RG,            // ReplayGain values kept in the app
        "netindex.json" to ExportCat.INDEX,           // the network locations' indexes (2026-11-28 01:12: no re-indexing after a reinstall)
    )
    /** Binary data files → category (sent as base64). */
    val BIN_FILES = mapOf(
        "tone.bin" to ExportCat.TONE,                 // Loudness / Treble / Preamp kept per song and station (01:12)
    )
    /** Never exported (this phone only): caches, logs, indexes, battery / crash / sleep notes, weather. */
    val LOCAL_PREFS = setOf("output", "alarms", "battery", "crashlog", "sleep", "volkeep", "unreadable")
    val LOCAL_FILES = setOf("state.json", "backups", "covers", "logs", "netindex-partial.json", "pictures", "rg-pending",
        "rgpatch", "subtitles-undo", "subtitles/", "tags.json")

    fun export(ctx: android.content.Context, cats: Set<String>): JSONObject {
        val o = JSONObject()
        val prefs = JSONObject()
        PREFS.filterValues { it in cats }.keys.forEach { name ->
            val p = JSONObject()
            ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> p.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> p.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> p.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> p.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> p.put(k, JSONObject().put("t", "s").put("v", v))
                    is Set<*> -> p.put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() })))
                }
            }
            prefs.put(name, p)
        }
        o.put("prefs", prefs)
        val files = JSONObject()
        FILES.filterValues { it in cats }.keys.forEach { name ->
            val f = File(ctx.filesDir, name)
            if (f.exists() && f.length() < (if (name == "netindex.json") 60_000_000 else 8_000_000)) files.put(name, f.readText())
        }
        o.put("files", files)
        val bins = JSONObject()
        BIN_FILES.filterValues { it in cats }.keys.forEach { name ->
            val f = File(ctx.filesDir, name)
            if (f.exists() && f.length() < 20_000_000) bins.put(name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
        }
        o.put("bins", bins)
        // File associations: which types are switched on (being the main app is Android's choice, set on the phone).
        if (ExportCat.ASSOC in cats) o.put("assoc", JSONObject().also { a ->
            app.vlcinterface.ui.Associations.TYPES.forEach { t -> a.put(t.key, app.vlcinterface.ui.Associations.registered(ctx, t)) }
        })
        return o
    }

    /** Returns true when something was written that the app reads at start (a restart finishes the import). */
    fun import(ctx: android.content.Context, o: JSONObject, cats: Set<String>, replace: Boolean): Boolean {
        var restart = false
        o.optJSONObject("prefs")?.let { all ->
            all.keys().forEach { name ->
                if (PREFS[name]?.let { it in cats } != true) return@forEach
                val p = all.getJSONObject(name)
                val sp = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                val e = sp.edit()
                if (replace) e.clear()
                p.keys().forEach { k ->
                    if (!replace && sp.contains(k)) return@forEach          // merge: mine kept, new ones added
                    val x = p.getJSONObject(k)
                    when (x.optString("t")) {
                        "b" -> e.putBoolean(k, x.getBoolean("v"))
                        "i" -> e.putInt(k, x.getInt("v"))
                        "l" -> e.putLong(k, x.getLong("v"))
                        "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                        "s" -> e.putString(k, x.getString("v"))
                        "ss" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    }
                }
                e.commit(); restart = true
            }
        }
        o.optJSONObject("files")?.let { all ->
            all.keys().forEach { name ->
                if (FILES[name]?.let { it in cats } != true) return@forEach
                val text = all.getString(name)
                val f = File(ctx.filesDir, name)
                val out = if (replace || !f.exists()) text else mergeText(f.readText(), text)
                File(ctx.filesDir, "$name.tmp").apply { writeText(out); renameTo(f) }
                restart = true
            }
        }
        if (ExportCat.ASSOC in cats) o.optJSONObject("assoc")?.let { a ->
            app.vlcinterface.ui.Associations.TYPES.forEach { t -> if (a.has(t.key)) app.vlcinterface.ui.Associations.setRegistered(ctx, t, a.getBoolean(t.key)) }
        }
        // Binary files (the tone kept per song / station): replaced, or added when this phone has none.
        o.optJSONObject("bins")?.let { all ->
            all.keys().forEach { name ->
                if (BIN_FILES[name]?.let { it in cats } != true) return@forEach
                val f = File(ctx.filesDir, name)
                if (!replace && f.exists() && f.length() > 0) return@forEach
                try { File(ctx.filesDir, "$name.tmp").apply { writeBytes(android.util.Base64.decode(all.getString(name), android.util.Base64.NO_WRAP)); renameTo(f) }; restart = true } catch (_: Exception) {}
            }
        }
        return restart
    }

    /** Merge: a JSON object keeps mine and adds the new keys; a JSON array or a text adds the new entries / lines. */
    private fun mergeText(mine: String, inc: String): String = try {
        val m = JSONObject(mine); val n = JSONObject(inc)
        n.keys().forEach { k -> if (!m.has(k)) m.put(k, n.get(k)) }; m.toString()
    } catch (_: Exception) {
        try {
            val m = JSONArray(mine); val n = JSONArray(inc)
            val have = (0 until m.length()).map { m.get(it).toString() }.toHashSet()
            (0 until n.length()).forEach { i -> if (n.get(i).toString() !in have) m.put(n.get(i)) }; m.toString()
        } catch (_: Exception) {
            (mine.lines() + inc.lines().filter { it.isNotBlank() && it !in mine.lines() }).joinToString("\n")
        }
    }
}
