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
