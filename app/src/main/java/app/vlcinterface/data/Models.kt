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
    /** Shares found read-only (writes to them are kept in the app); "*" = the whole server. */
    val readOnly: List<String> = emptyList(),
    /** The same server reached through the VPN (e.g. ZeroTier): used when the VPN is on and you're not on its LAN. */
    val vpnHost: String = "",
    /** How the server identifies itself (to recognise it on the VPN): SMB server GUID, NetBIOS name, MAC. */
    val smbGuid: String = "",
    val nbName: String = "",
    val mac: String = "",
    /** Which address to use: "auto" (VPN address when away with the VPN on), "lan" or "vpn" (chosen by hand). */
    val addrMode: String = "auto",
    /** Other addresses of the same server (VPN ones, another subnet …), switchable by hand in Libraries → LAN. */
    val altHosts: List<String> = emptyList(),
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
        .put("lastScan", lastScan).put("readOnly", JSONArray(readOnly))
        .put("vpnHost", vpnHost).put("smbGuid", smbGuid).put("nbName", nbName).put("mac", mac).put("addrMode", addrMode).put("altHosts", JSONArray(altHosts))

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
            readOnly = o.optJSONArray("readOnly").strings(),
            vpnHost = o.optString("vpnHost"),
            smbGuid = o.optString("smbGuid"),
            nbName = o.optString("nbName"),
            mac = o.optString("mac"),
            addrMode = o.optString("addrMode", "auto").ifBlank { "auto" },
            altHosts = o.optJSONArray("altHosts").strings().let { l -> (l + listOfNotNull(o.optString("vpnHost").takeIf { it.isNotBlank() })).distinct() },
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
    /** Old look choice (outline / shadow / box / none): only read to start the new settings from it. */
    val edges: String = "outline",
    val position: String = "bottom",
    val edgePct: Int = 8,
    /** Border round the letters (2026-11-21 16:05): colour (RGB) and width 0 (none) … 10. */
    val outlineColor: Int = 0x000000,
    val outlineWidth: Int = 4,
    /** Shadow: colour, width (how far it falls, 0 = none … 10), direction (degrees: 0 right, 90 up, 270 down …),
     *  transparency 0 (solid) … 100 % (invisible). */
    val shadowColor: Int = 0x000000,
    val shadowWidth: Int = 0,
    val shadowAngle: Int = 315,
    val shadowTransp: Int = 30,
    /** Dark box behind the text. */
    val box: Boolean = false,
    /** Height on the picture: 1 = at the bottom … 10 = near the top, in 10 equal steps. */
    val posStep: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().put("font", font).put("sizePct", sizePct).put("bold", bold)
        .put("color", color).put("edges", edges).put("position", position).put("edgePct", edgePct)
        .put("outlineColor", outlineColor).put("outlineWidth", outlineWidth).put("shadowColor", shadowColor)
        .put("shadowWidth", shadowWidth).put("shadowAngle", shadowAngle).put("shadowTransp", shadowTransp)
        .put("box", box).put("posStep", posStep).put("v2", true)

    companion object {
        fun fromJson(o: JSONObject?): SubStyle {
            if (o == null) return SubStyle()
            val edges = o.optString("edges", "outline")
            val position = o.optString("position", "bottom")
            val edgePct = o.optInt("edgePct", 8)
            val v2 = o.optBoolean("v2", false)
            // Saved before the detailed settings: started from the old choice.
            val oldStep = if (position == "top") 10 else (edgePct / 9 + 1).coerceIn(1, 10)
            return SubStyle(
                o.optString("font", "sans-serif"), o.optInt("sizePct", 100), o.optBoolean("bold"),
                o.optInt("color", 0xFFFFFF), edges, position, edgePct,
                o.optInt("outlineColor", 0x000000), if (v2) o.optInt("outlineWidth", 4) else if (edges == "outline") 4 else 0,
                o.optInt("shadowColor", 0x000000), if (v2) o.optInt("shadowWidth", 0) else if (edges == "shadow") 3 else 0,
                o.optInt("shadowAngle", 315), o.optInt("shadowTransp", 30),
                if (v2) o.optBoolean("box", false) else edges == "box",
                if (v2) o.optInt("posStep", 1).coerceIn(1, 10) else oldStep,
            )
        }
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
    /** Menus (and search result lists): space above and below each row, dp (0–16). */
    val menuGap: Int = 6,
    /** VPN (Settings → VPN): home NAS through the VPN on / off, ask to start it when away, which VPN app to open. */
    val vpnNas: Boolean = true,
    val vpnAskStart: Boolean = true,
    val vpnApp: String = "auto",
    /** "Retry in … s" of the "start the VPN" question (time to start it). */
    val vpnRetrySec: Int = 40,
    /** Loudness / Treble / Preamp sliders on the players: shown and applied (off = gone and no processing). */
    val toneSliders: Boolean = true,
    /** Players: faint light-grey shadow (down-right) on buttons and slider knobs, knobs ringed in grey. */
    val controlShadow: Boolean = true,
    /** Auto loudness: Loudness follows the phone's media volume (full up to each device's start %, then flattening). */
    val autoLoud: Boolean = false,
    /** Loudness / treble last used (kept for songs without their own values, also after a restart). */
    val toneLoud: Float = 0f,
    val toneTreble: Float = 0f,
    /** Radio pre-amp last used (a station played for the first time starts with it). */
    val radioPreLast: Float = 0f,
    /** Song pre-amp last used (songs without their own value start with it). */
    val songPreLast: Float = 0f,
    /** Accent colour while a radio station plays (0 = same as for files). */
    val radioAccent: Long = 0L,
    /** How many songs "Up next" shows on the player (0–20). */
    val upNextCount: Int = 2,
    /** Saved radio songs: folder (Android folder access; empty = Music/Saved songs), "mp3" (LAME V0) or "original". */
    val saveFolder: String = "",
    val saveFormat: String = "mp3",
    /** Keep the playing station recorded so its songs can be saved: "wifi", "always" or "off". */
    val radioCapture: String = "wifi",
    /** Width of the lists' scroll bar in pixels (4–9); drag it to scroll fast. */
    val scrollbarPx: Int = 6,
    /** Radio recording and free space: under [spaceUnderGb] GB free (0 = never), at most [spacePercent] % of it. */
    val spaceUnderGb: Int = 100,
    /** Radio buffer in minutes (1–20): the recording kept for saving, and how long a lost connection may take. */
    val radioBufferMin: Int = 5,
    /** Spotify developer app (free, developer.spotify.com): song links found by Spotify's own search. */
    val spotifyId: String = "",
    val spotifySecret: String = "",
    val spacePercent: Int = 30,
    /** Colours picked by hand in Settings (up to 5). */
    val customAccents: List<Long> = emptyList(),
    /** Bluetooth quality mode: on a Bluetooth output the app's own volume stays at 100 % (the device volume is used)
     *  and the sound is sent at the Bluetooth link's rate. */
    val btQuality: Boolean = false,
    /** Order of the links on the main Settings page (group ids); empty = the original order. */
    val settingsOrder: List<String> = emptyList(),
    /** The setup wizard was finished or closed (else it opens at start). */
    val wizardDone: Boolean = false,
    /** Phone media places added after approval ("volume:Top/"); used once [phoneFoldersSet] (first install / Find media folders). */
    val phoneFolders: List<String> = emptyList(),
    val phoneFoldersSet: Boolean = false,
    /** Floating play / pause on the players: size (dp), opacity (%), place (fractions of the player; -1 = default corner). */
    val floatBtnSize: Int = 64,
    val floatBtnAlpha: Int = 85,
    val floatBtnX: Float = -1f,
    val floatBtnY: Float = -1f,
    /** Height of the alarm rows under the clock, % of the old height (50–100). */
    val alarmRowPct: Int = 50,
    /** How long short, self-closing pop-ups stay (seconds). */
    val shortPopupSec: Int = 3,
    /** Switch track colours: on (0 = accent) and off. */
    val switchOn: Long = 0L,
    val switchOff: Long = 0xFF4A5568,
    /** Floating play / pause colour (0 = accent). */
    val floatBtnColor: Long = 0L,
    /** Upright player: seconds the clock stays hidden after the last action once scrolling hid it. */
    val clockHideSec: Int = 10,
    /** Upright player: seconds the album cover stays hidden after the last action once scrolling hid it. */
    val coverHideSec: Int = 10,
    /** Floating play / pause: "always", "scroll" (only when the buttons scroll away) or "off". */
    val floatBtnMode: String = "always",
    /** Upright player: the clock / album picture scroll away and stay hidden while you act (off: clock pinned, picture just scrolls). */
    val clockAutoHide: Boolean = true,
    val coverAutoHide: Boolean = true,
    /** Order of the player rows (row ids; empty = the original order), songs and radio apart. */
    val rowOrderSong: List<String> = emptyList(),
    val rowOrderRadio: List<String> = emptyList(),
    /** Video player upright: order of the rows under the picture (Look → "Order of the player rows" → Video). */
    val rowOrderVideo: List<String> = emptyList(),
    /** Player rows removed with ✕ in Look (not shown on that player), songs and radio apart. */
    val rowHiddenSong: List<String> = emptyList(),
    val rowHiddenRadio: List<String> = emptyList(),
    /** Settings links removed with ✕ in Look (not shown on the main Settings page). */
    val settingsHidden: List<String> = emptyList(),
    /** Horizontal autoscroll speed per list (list key → %, 0 = still, 100 = normal); see [scrollSpeedOf]. */
    val listScroll: Map<String, Int> = emptyMap(),
    /** Phone songs without ReplayGain: their whole folder is scanned silently (track gain kept in the app). */
    val rgAutoFolder: Boolean = true,
    /** Gap between the player's rows (dp). */
    val playerGap: Int = 4,
    /** Text size in Settings, the wizard, the help guide and helper pop-ups (%). */
    val settingsTextPct: Int = 100,
    /** Vertical spacing in Settings, the wizard and the help guide (%). */
    val settingsGapPct: Int = 85,
    /** Tall videos upright: full screen (true) or the upper two thirds with the buttons below. */
    val videoTallFull: Boolean = false,
    /** Video player: size of the on-screen buttons (side columns sideways, buttons under the picture upright), % (17:13). */
    val videoBtnPct: Int = 100,
    /** Size of every page title, % of 22 dp letters (Settings main page, 18:05). */
    val pageTitlePct: Int = 100,
    /** Treble band (2026-11-21 21:50): "shelf" (lifts everything above) or "peak" (one bell band); frequency in Hz;
     *  shelf slope / peak width (Q); dB at the Treble slider's ends (±5). */
    val trebleType: String = "shelf",
    val trebleHz: Int = 4000,
    val trebleSlope: Float = 0.6f,
    val trebleQ: Float = 1.0f,
    val trebleMaxDb: Float = 10f,
    /** Visualisation always shown in the album picture's place: "off", "songs", "radio" or "both". */
    val visInsteadCover: String = "off",
    /** Video decoding: "auto" (hardware, software when it can't), "hw" (hardware only), "sw" (software only). */
    val videoDecoder: String = "auto",
    /** Deinterlacing: "auto" (by the video's encoding), "on", "off". */
    val videoDeinterlace: String = "auto"
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
        .put("pictureHue", pictureHue.toDouble()).put("pictureSat", pictureSat.toDouble()).put("pictureHighlights", pictureHighlights.toDouble()).put("libraryKind", libraryKind).put("buttonScale", buttonScale).put("coverDownload", coverDownload).put("rgLastMode", rgLastMode).put("rgChoices", JSONObject(rgChoices)).put("pictureByFile", JSONObject(pictureByFile)).put("songsScrollSpeed", songsScrollSpeed).put("autoRotate", autoRotate).put("sessionDay", sessionDay).put("sessionPlaylistId", sessionPlaylistId).put("sessionStartHour", sessionStartHour).put("sessionManual", sessionManual).put("sessionLog", org.json.JSONArray().also { a -> sessionLog.forEach { e -> a.put(e.toJson()) } }).put("hiddenUris", org.json.JSONArray(hiddenUris.toList())).put("popupOpacity", popupOpacity).put("menuFont", menuFont).put("customAccents", org.json.JSONArray(customAccents)).put("menuOpacity", menuOpacity).put("menuGap", menuGap).put("vpnNas", vpnNas).put("vpnAskStart", vpnAskStart).put("vpnApp", vpnApp).put("vpnRetrySec", vpnRetrySec).put("upNextCount", upNextCount).put("radioAccent", radioAccent).put("autoLoud", autoLoud).put("toneSliders", toneSliders).put("controlShadow", controlShadow).put("toneLoud", toneLoud.toDouble()).put("toneTreble", toneTreble.toDouble()).put("radioPreLast", radioPreLast.toDouble()).put("songPreLast", songPreLast.toDouble()).put("saveFolder", saveFolder).put("saveFormat", saveFormat).put("radioCapture", radioCapture).put("scrollbarPx", scrollbarPx).put("spaceUnderGb", spaceUnderGb).put("radioBufferMin", radioBufferMin).put("spotifyId", spotifyId).put("spotifySecret", spotifySecret).put("spacePercent", spacePercent).put("btQuality", btQuality).put("settingsOrder", org.json.JSONArray(settingsOrder)).put("wizardDone", wizardDone).put("phoneFolders", org.json.JSONArray(phoneFolders)).put("phoneFoldersSet", phoneFoldersSet).put("floatBtnSize", floatBtnSize).put("floatBtnAlpha", floatBtnAlpha).put("floatBtnX", floatBtnX.toDouble()).put("floatBtnY", floatBtnY.toDouble())
            .put("alarmRowPct", alarmRowPct).put("shortPopupSec", shortPopupSec).put("switchOn", switchOn).put("switchOff", switchOff).put("floatBtnColor", floatBtnColor).put("clockHideSec", clockHideSec).put("coverHideSec", coverHideSec).put("floatBtnMode", floatBtnMode).put("clockAutoHide", clockAutoHide).put("coverAutoHide", coverAutoHide).put("rowOrderSong", org.json.JSONArray(rowOrderSong)).put("rowOrderRadio", org.json.JSONArray(rowOrderRadio)).put("rowOrderVideo", org.json.JSONArray(rowOrderVideo)).put("rowHiddenSong", org.json.JSONArray(rowHiddenSong)).put("rowHiddenRadio", org.json.JSONArray(rowHiddenRadio)).put("settingsHidden", org.json.JSONArray(settingsHidden)).put("listScroll", JSONObject(listScroll as Map<*, *>)).put("rgAutoFolder", rgAutoFolder).put("playerGap", playerGap).put("settingsTextPct", settingsTextPct).put("settingsGapPct", settingsGapPct).put("videoTallFull", videoTallFull).put("videoBtnPct", videoBtnPct).put("pageTitlePct", pageTitlePct).put("trebleType", trebleType).put("trebleHz", trebleHz).put("trebleSlope", trebleSlope.toDouble()).put("trebleQ", trebleQ.toDouble()).put("trebleMaxDb", trebleMaxDb.toDouble()).put("visInsteadCover", visInsteadCover).put("videoDecoder", videoDecoder).put("videoDeinterlace", videoDeinterlace)

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
                menuOpacity = o.optInt("menuOpacity", 90).coerceIn(20, 100),
                menuGap = o.optInt("menuGap", 6).coerceIn(0, 16),
                vpnNas = o.optBoolean("vpnNas", true),
                vpnAskStart = o.optBoolean("vpnAskStart", true),
                vpnRetrySec = o.optInt("vpnRetrySec", 40).coerceIn(10, 300),
                vpnApp = o.optString("vpnApp", "auto").let { if (it in setOf("auto", "zerotier", "tailscale", "settings")) it else "auto" },
                upNextCount = o.optInt("upNextCount", 2).coerceIn(0, 20),
                radioAccent = o.optLong("radioAccent", 0L),
                autoLoud = o.optBoolean("autoLoud", false),
                toneSliders = o.optBoolean("toneSliders", true),
                controlShadow = o.optBoolean("controlShadow", true),
                toneLoud = o.optDouble("toneLoud", 0.0).toFloat().coerceIn(0f, 15f),
                toneTreble = o.optDouble("toneTreble", 0.0).toFloat().coerceIn(-5f, 5f),
                radioPreLast = o.optDouble("radioPreLast", 0.0).toFloat().coerceIn(-12f, 12f),
                songPreLast = o.optDouble("songPreLast", 0.0).toFloat().coerceIn(-12f, 12f),
                saveFolder = o.optString("saveFolder", ""),
                saveFormat = o.optString("saveFormat", "mp3").let { if (it == "original") it else "mp3" },
                radioCapture = o.optString("radioCapture", "wifi").let { if (it in setOf("wifi", "always", "off")) it else "wifi" },
                scrollbarPx = o.optInt("scrollbarPx", 6).coerceIn(4, 9),
                spaceUnderGb = o.optInt("spaceUnderGb", 100).coerceIn(0, 4096),
                radioBufferMin = o.optInt("radioBufferMin", 5).coerceIn(1, 20),
                spotifyId = o.optString("spotifyId", ""),
                spotifySecret = o.optString("spotifySecret", ""),
                spacePercent = o.optInt("spacePercent", 30).coerceIn(5, 90),
                btQuality = o.optBoolean("btQuality", false),
                wizardDone = o.optBoolean("wizardDone", false),
                phoneFolders = o.optJSONArray("phoneFolders")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                phoneFoldersSet = o.optBoolean("phoneFoldersSet", false),
                floatBtnSize = o.optInt("floatBtnSize", 64), floatBtnAlpha = o.optInt("floatBtnAlpha", 85),
                floatBtnX = o.optDouble("floatBtnX", -1.0).toFloat(), floatBtnY = o.optDouble("floatBtnY", -1.0).toFloat(),
                alarmRowPct = o.optInt("alarmRowPct", 50), shortPopupSec = o.optInt("shortPopupSec", 3),
                switchOn = o.optLong("switchOn", 0L), switchOff = o.optLong("switchOff", 0xFF4A5568), floatBtnColor = o.optLong("floatBtnColor", 0L), clockHideSec = o.optInt("clockHideSec", 10), coverHideSec = o.optInt("coverHideSec", 10), floatBtnMode = o.optString("floatBtnMode", "always"), clockAutoHide = o.optBoolean("clockAutoHide", true), coverAutoHide = o.optBoolean("coverAutoHide", true),
                rowOrderSong = o.optJSONArray("rowOrderSong")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                rowOrderRadio = o.optJSONArray("rowOrderRadio")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                rowOrderVideo = o.optJSONArray("rowOrderVideo")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                rowHiddenSong = o.optJSONArray("rowHiddenSong")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                rowHiddenRadio = o.optJSONArray("rowHiddenRadio")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                settingsHidden = o.optJSONArray("settingsHidden")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                rgAutoFolder = o.optBoolean("rgAutoFolder", true),
                listScroll = o.optJSONObject("listScroll")?.let { m -> m.keys().asSequence().associateWith { k -> m.optInt(k, 100).coerceIn(0, 300) } } ?: emptyMap(),
                playerGap = o.optInt("playerGap", 4), settingsTextPct = o.optInt("settingsTextPct", 100), settingsGapPct = o.optInt("settingsGapPct", 85), videoTallFull = o.optBoolean("videoTallFull", false), videoBtnPct = o.optInt("videoBtnPct", 100), pageTitlePct = o.optInt("pageTitlePct", 100),
                trebleType = o.optString("trebleType", "shelf").let { if (it == "peak") "peak" else "shelf" }, trebleHz = o.optInt("trebleHz", 4000).coerceIn(1000, 16000),
                trebleSlope = o.optDouble("trebleSlope", 0.6).toFloat().coerceIn(0.3f, 1f), trebleQ = o.optDouble("trebleQ", 1.0).toFloat().coerceIn(0.3f, 4f),
                trebleMaxDb = o.optDouble("trebleMaxDb", 10.0).toFloat().coerceIn(4f, 15f), visInsteadCover = o.optString("visInsteadCover", "off"), videoDecoder = o.optString("videoDecoder", "auto"), videoDeinterlace = o.optString("videoDeinterlace", "auto"),
                settingsOrder = o.optJSONArray("settingsOrder")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
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

/**
 * Autoscroll speed of a list's long names (%, 0 = still): radioFav, radioFind, songs, folders, videos, player.
 * The Songs list starts from the older "Songs list" setting; "upnext" is that older setting itself.
 */
fun scrollSpeedOf(s: Settings, key: String?): Int = when (key) {
    null, "upnext" -> s.songsScrollSpeed
    else -> s.listScroll[key] ?: if (key == "songs") s.songsScrollSpeed else 100
}

