package app.vlcinterface.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Zone { LAN, INTERNET }

/** proto: smb, nfs, sftp, ftp, ftps, upnp, webdav, http, m3u */
data class Source(
    val id: String = UUID.randomUUID().toString(),
    val zone: Zone = Zone.LAN,
    val proto: String = "smb",
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val path: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val wifiOnly: Boolean = false,
    val pins: List<String> = emptyList(),
    val smbVersion: String = "",
    val lastScan: Long = 0L,
) {
    val defaultPort: Int
        get() = when (proto) {
            "smb" -> 445; "sftp" -> 22; "ftp", "ftps" -> 21; "nfs" -> 2049
            "webdav", "http", "m3u" -> if (host.startsWith("http://")) 80 else 443
            else -> 0
        }

    fun toJson(passwordTransform: (String) -> String): JSONObject = JSONObject()
        .put("id", id).put("zone", zone.name).put("proto", proto).put("name", name)
        .put("host", host).put("port", port).put("path", path).put("user", user)
        .put("password", passwordTransform(password)).put("domain", domain)
        .put("wifiOnly", wifiOnly).put("pins", JSONArray(pins)).put("smbVersion", smbVersion)
        .put("lastScan", lastScan)

    companion object {
        fun fromJson(o: JSONObject, passwordTransform: (String) -> String) = Source(
            id = o.optString("id", UUID.randomUUID().toString()),
            zone = runCatching { Zone.valueOf(o.optString("zone", "LAN")) }.getOrDefault(Zone.LAN),
            proto = o.optString("proto", "smb"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 0),
            path = o.optString("path"),
            user = o.optString("user"),
            password = passwordTransform(o.optString("password")),
            domain = o.optString("domain"),
            wifiOnly = o.optBoolean("wifiOnly", false),
            pins = o.optJSONArray("pins").strings(),
            smbVersion = o.optString("smbVersion"),
            lastScan = o.optLong("lastScan", 0L),
        )
    }
}

data class Station(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val meta: String = "",
    val favicon: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("meta", meta).put("favicon", favicon)

    companion object {
        fun fromJson(o: JSONObject) = Station(
            o.optString("id", UUID.randomUUID().toString()), o.optString("name"),
            o.optString("url"), o.optString("meta"), o.optString("favicon")
        )
    }
}

data class SubStyle(
    val font: String = "sans-serif",
    val sizePct: Int = 100,
    val bold: Boolean = false,
    val color: Int = 0xFFFFFF,
    val edges: String = "outline", // outline, shadow, box, none
    val position: String = "bottom", // bottom, top
    val edgePct: Int = 8,
) {
    fun toJson(): JSONObject = JSONObject().put("font", font).put("sizePct", sizePct).put("bold", bold)
        .put("color", color).put("edges", edges).put("position", position).put("edgePct", edgePct)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SubStyle() else SubStyle(
            o.optString("font", "sans-serif"), o.optInt("sizePct", 100), o.optBoolean("bold"),
            o.optInt("color", 0xFFFFFF), o.optString("edges", "outline"),
            o.optString("position", "bottom"), o.optInt("edgePct", 8)
        )
    }
}

data class Settings(
    val volumeSwipe: Boolean = true,
    val brightnessSwipe: Boolean = true,
    val doubleTapSeekSec: Int = 10,
    val holdForSpeed: Boolean = true,
    val resume: Boolean = true,
    val backgroundAudio: Boolean = true,
    val subLangs: List<String> = listOf("en"),
    val autoDownloadSubs: Boolean = true,
    val autoScan: Boolean = false,
    val watchFolders: Boolean = false,
    val accent: Long = 0xFFFF8A3D,
    val syncPanelOpacity: Int = 50,
    val osApiKey: String = "",
    val osUser: String = "",
    val osPassword: String = "",
    val pauseOnDisconnect: Boolean = true,
    val rememberOutputPerDevice: Boolean = true,
    val sameOutputVideoRadio: Boolean = true,
    val startVolume: Int = 40,
    val preampDb: Float = 0f,
    val rgMode: String = "auto", // off, track, album, auto
    val rgPreampDb: Float = 0f,
    val rgClipProtect: Boolean = true,
    val allowSmb1: Boolean = true,
    val repeat: String = "off", // off, all, one
    val shuffle: Boolean = false,
    /** "app" = the app's own output (balance, preamp, meters), falls back by itself; "libvlc" = libVLC's output. */
    val soundEngine: String = "app",
    /** Picture colour: hue −180…180°, saturation 0…3 (1 = unchanged). */
    val pictureHue: Float = 0f,
    val pictureSat: Float = 1f,
    /** Tone down burnt-out white areas on video: 0 = off … 1 = strongest (saved per file, see pictureByFile). */
    val pictureHighlights: Float = 0f,
    /** Libraries show "audio" or "video" files (switch at the top of Libraries). */
    val libraryKind: String = "audio",
    /** Size of every symbol button and its tap area, in % of standard (default 75 % = 25 % smaller). */
    val buttonScale: Int = 75,
    /** Download album pictures (MusicBrainz / Cover Art Archive) for albums without one; stored in the app. */
    val coverDownload: Boolean = true,
    /** ReplayGain mode to go back to when the player's RG button turns it on again. */
    val rgLastMode: String = "auto",
    /** Per album (album | folder): "album" or "track" gain, picked with the player's RG button. Wins over the mode above. */
    val rgChoices: Map<String, String> = emptyMap(),
    /** Video picture settings saved per file (uri → "hue,saturation,brightAreas"). */
    val pictureByFile: Map<String, String> = emptyMap(),
    /** Songs list: speed of the long rows gliding to their end and back, % of normal (0 = still). */
    val songsScrollSpeed: Int = 100,
    /** The app turns with the phone (on) or stays upright (off; the video player's rotate button still works). */
    val autoRotate: Boolean = false,
    /** Today's listening-session playlist: its day (yyyy-MM-dd), id and first hour. */
    val sessionDay: String = "",
    val sessionPlaylistId: String = "",
    val sessionStartHour: Int = 0,
    /** Everything played today (the day in [sessionDay]), kept even before today's session list exists. */
    val sessionLog: List<PlEntry> = emptyList(),
    /** Something was chosen by hand today (added, picked, skipped to): today's session list is kept. */
    val sessionManual: Boolean = false,
    /** Files taken out of the Library lists ("Remove from this list"); the files themselves are untouched. */
    val hiddenUris: Set<String> = emptySet(),
    /** Pop-up windows, search lists and menus: opacity in % (50–100). */
    val popupOpacity: Int = 90,
    /** Text size in long-press / ⋮ menus: small, medium, large. */
    val menuFont: String = "medium",
    /** Long-press / ⋮ menus: opacity in % (50–100). */
    val menuOpacity: Int = 90,
    /** Auto loudness: Loudness follows the phone's media volume (full up to each device's start %, then flattening). */
    val autoLoud: Boolean = false,
    /** Loudness / treble last used (kept for songs without their own values, also after a restart). */
    val toneLoud: Float = 0f,
    val toneTreble: Float = 0f,
    /** Accent colour while a radio station plays (0 = same as for files). */
    val radioAccent: Long = 0L,
    /** How many songs "Up next" shows on the player (0–20). */
    val upNextCount: Int = 2,
    /** Colours picked by hand in Settings (up to 5). */
    val customAccents: List<Long> = emptyList(),
) {
    fun toJson(passwordTransform: (String) -> String): JSONObject = JSONObject()
        .put("volumeSwipe", volumeSwipe).put("brightnessSwipe", brightnessSwipe)
        .put("doubleTapSeekSec", doubleTapSeekSec).put("holdForSpeed", holdForSpeed)
        .put("resume", resume).put("backgroundAudio", backgroundAudio)
        .put("subLangs", JSONArray(subLangs)).put("autoDownloadSubs", autoDownloadSubs)
        .put("autoScan", autoScan).put("watchFolders", watchFolders).put("accent", accent)
        .put("syncPanelOpacity", syncPanelOpacity).put("osApiKey", passwordTransform(osApiKey))
        .put("osUser", osUser).put("osPassword", passwordTransform(osPassword))
        .put("pauseOnDisconnect", pauseOnDisconnect)
        .put("rememberOutputPerDevice", rememberOutputPerDevice)
        .put("sameOutputVideoRadio", sameOutputVideoRadio)
        .put("startVolume", startVolume).put("preampDb", preampDb.toDouble()).put("rgMode", rgMode)
        .put("rgPreampDb", rgPreampDb.toDouble()).put("rgClipProtect", rgClipProtect).put("allowSmb1", allowSmb1)
        .put("repeat", repeat).put("shuffle", shuffle).put("soundEngine", soundEngine)
        .put("pictureHue", pictureHue.toDouble()).put("pictureSat", pictureSat.toDouble()).put("pictureHighlights", pictureHighlights.toDouble()).put("libraryKind", libraryKind).put("buttonScale", buttonScale).put("coverDownload", coverDownload).put("rgLastMode", rgLastMode).put("rgChoices", JSONObject(rgChoices)).put("pictureByFile", JSONObject(pictureByFile)).put("songsScrollSpeed", songsScrollSpeed).put("autoRotate", autoRotate).put("sessionDay", sessionDay).put("sessionPlaylistId", sessionPlaylistId).put("sessionStartHour", sessionStartHour).put("sessionManual", sessionManual).put("sessionLog", org.json.JSONArray().also { a -> sessionLog.forEach { e -> a.put(e.toJson()) } }).put("hiddenUris", org.json.JSONArray(hiddenUris.toList())).put("popupOpacity", popupOpacity).put("menuFont", menuFont).put("customAccents", org.json.JSONArray(customAccents)).put("menuOpacity", menuOpacity).put("upNextCount", upNextCount).put("radioAccent", radioAccent).put("autoLoud", autoLoud).put("toneLoud", toneLoud.toDouble()).put("toneTreble", toneTreble.toDouble())

    companion object {
        fun fromJson(o: JSONObject?, passwordTransform: (String) -> String): Settings {
            if (o == null) return Settings()
            val d = Settings()
            return Settings(
                o.optBoolean("volumeSwipe", d.volumeSwipe), o.optBoolean("brightnessSwipe", d.brightnessSwipe),
                o.optInt("doubleTapSeekSec", d.doubleTapSeekSec), o.optBoolean("holdForSpeed", d.holdForSpeed),
                o.optBoolean("resume", d.resume), o.optBoolean("backgroundAudio", d.backgroundAudio),
                o.optJSONArray("subLangs").strings().ifEmpty { d.subLangs },
                o.optBoolean("autoDownloadSubs", d.autoDownloadSubs), o.optBoolean("autoScan", false),
                o.optBoolean("watchFolders", false), o.optLong("accent", d.accent),
                o.optInt("syncPanelOpacity", 50).coerceIn(20, 80),
                passwordTransform(o.optString("osApiKey")), o.optString("osUser"),
                passwordTransform(o.optString("osPassword")),
                o.optBoolean("pauseOnDisconnect", true), o.optBoolean("rememberOutputPerDevice", true),
                o.optBoolean("sameOutputVideoRadio", true),
                o.optInt("startVolume", 40).coerceIn(0, 100),
                o.optDouble("preampDb", 0.0).toFloat().coerceIn(-12f, 12f),
                o.optString("rgMode", "auto"),
                o.optDouble("rgPreampDb", 0.0).toFloat().coerceIn(-6f, 6f),
                o.optBoolean("rgClipProtect", true),
                o.optBoolean("allowSmb1", true),
                o.optString("repeat", "off"),
                o.optBoolean("shuffle", false),
                o.optString("soundEngine", "app"),
                o.optDouble("pictureHue", 0.0).toFloat().coerceIn(-20f, 20f),
                o.optDouble("pictureSat", 1.0).toFloat().coerceIn(0f, 3f),
                o.optDouble("pictureHighlights", 0.0).toFloat().coerceIn(0f, 1f),
                o.optString("libraryKind", "audio").let { if (it == "video") "video" else "audio" },
                o.optInt("buttonScale", 75).coerceIn(50, 125),
                o.optBoolean("coverDownload", true),
                o.optString("rgLastMode", "auto").let { if (it in setOf("track", "album", "auto")) it else "auto" },
                o.optJSONObject("rgChoices")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: emptyMap(),
                o.optJSONObject("pictureByFile")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: emptyMap(),
                o.optInt("songsScrollSpeed", 100).coerceIn(0, 300),
                o.optBoolean("autoRotate", false),
                o.optString("sessionDay", ""), o.optString("sessionPlaylistId", ""), o.optInt("sessionStartHour", 0),
                o.optJSONArray("sessionLog")?.let { a -> (0 until a.length()).map { PlEntry.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                o.optBoolean("sessionManual", false),
                o.optJSONArray("hiddenUris")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                popupOpacity = o.optInt("popupOpacity", 90).coerceIn(50, 100),
                menuFont = o.optString("menuFont", "medium").let { if (it in setOf("small", "medium", "large", "xlarge")) it else "medium" },
                menuOpacity = o.optInt("menuOpacity", 90).coerceIn(50, 100),
                upNextCount = o.optInt("upNextCount", 2).coerceIn(0, 20),
                radioAccent = o.optLong("radioAccent", 0L),
                autoLoud = o.optBoolean("autoLoud", false),
                toneLoud = o.optDouble("toneLoud", 0.0).toFloat().coerceIn(0f, 15f),
                toneTreble = o.optDouble("toneTreble", 0.0).toFloat().coerceIn(-12f, 12f),
                customAccents = ((o.optJSONArray("customAccents")?.let { a -> (0 until a.length()).map { a.getLong(it) } }
                    ?: listOfNotNull(o.optLong("customAccent", 0L).takeIf { it != 0L }))
                    // A colour in use from the old preset row (blue / green / purple) stays as a custom one.
                    + listOfNotNull(o.optLong("accent", 0L).takeIf { it in listOf(0xFF4FB3FFL, 0xFF7BD88FL, 0xFFC792EAL) })).distinct().take(5),
            )
        }
    }
}

data class RecentItem(
    val uri: String,
    val title: String,
    val position: Long,
    val length: Long,
    val sourceId: String = "",
    val at: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("position", position)
        .put("length", length).put("sourceId", sourceId).put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = RecentItem(
            o.optString("uri"), o.optString("title"), o.optLong("position"), o.optLong("length"),
            o.optString("sourceId"), o.optLong("at")
        )
    }
}

/** One entry of a playlist or the queue. startMs/endMs are used for tracks cut from a single file by a .cue sheet. */
data class PlEntry(
    val uri: String,
    val title: String,
    val sourceId: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val startMs: Long = -1,
    val endMs: Long = -1,
    val trackNo: Int = 0,
    val folderKey: String = "",
    val fileName: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("sourceId", sourceId)
        .put("artist", artist).put("album", album).put("durationMs", durationMs).put("startMs", startMs)
        .put("endMs", endMs).put("trackNo", trackNo).put("folderKey", folderKey).put("fileName", fileName)

    /** Identity for de-duplication (same file and same cut). */
    val key: String get() = "$uri#$startMs"

    companion object {
        fun fromJson(o: JSONObject) = PlEntry(
            o.optString("uri"), o.optString("title"), o.optString("sourceId"), o.optString("artist"),
            o.optString("album"), o.optLong("durationMs"), o.optLong("startMs", -1), o.optLong("endMs", -1),
            o.optInt("trackNo"), o.optString("folderKey"), o.optString("fileName"),
        )
    }
}

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val items: List<PlEntry> = emptyList(),
    val lastIndex: Int = 0,
    val lastPosition: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("items", JSONArray(items.map { it.toJson() })).put("lastIndex", lastIndex).put("lastPosition", lastPosition)

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val a = o.optJSONArray("items") ?: JSONArray()
            return Playlist(o.optString("id", UUID.randomUUID().toString()), o.optString("name"),
                (0 until a.length()).map { PlEntry.fromJson(a.getJSONObject(it)) }, o.optInt("lastIndex"), o.optLong("lastPosition"))
        }
    }
}

data class AppState(
    val settings: Settings = Settings(),
    val sources: List<Source> = emptyList(),
    val stations: List<Station> = emptyList(),
    val subStyle: SubStyle = SubStyle(),
    /** keys "file:<uri>" or "folder:<folder uri>", value ms */
    val subOffsets: Map<String, Long> = emptyMap(),
    /** keys "file:<uri>" or "device:<output name>", value ms */
    val audioOffsets: Map<String, Long> = emptyMap(),
    val recent: List<RecentItem> = emptyList(),
    /** internal folder id -> last scan time */
    val scanned: Map<String, Long> = emptyMap(),
    val lastOutput: String = "",
    val playlists: List<Playlist> = emptyList(),
    /** output device name -> extra preamp dB */
    val devicePreamp: Map<String, Float> = emptyMap(),
    /** output device name -> balance -100 (left) .. 100 (right) */
    val deviceBalance: Map<String, Int> = emptyMap(),
    /** output device name -> phone volume % above which Auto loudness starts flattening (default 50) */
    val deviceLoudFrom: Map<String, Int> = emptyMap(),
)

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, null) }
}

fun JSONObject?.longMap(): Map<String, Long> {
    if (this == null) return emptyMap()
    val m = LinkedHashMap<String, Long>()
    keys().forEach { k -> m[k] = optLong(k) }
    return m
}

fun Map<String, Long>.toJson(): JSONObject = JSONObject().also { o -> forEach { (k, v) -> o.put(k, v) } }
