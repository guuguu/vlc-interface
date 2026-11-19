package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The help guide: every capability of the app, in chapters and pages. */
object HelpGuide {
    class Chapter(val id: String, val title: String, val pages: List<Pair<String, String>>)

    val chapters: List<Chapter> = listOf(
        Chapter("start", "Getting started", listOf(
            "What the app does" to "vlc interface plays your music, videos and radio on top of libVLC, the engine of VLC. It reads songs from the phone, " +
                "from your NAS at home (SMB, NFS, SFTP, WebDAV, UPnP…) and from Internet sources, keeps playlists, records radio songs, wakes you " +
                "with alarms and shows a clock and visualisations while music plays.\n\nEverything can be set later in Settings: the setup wizard only " +
                "gathers the important choices in one walk-through.",
            "Moving around" to "The bottom bar has Library, Playlists and Settings. Every page has a back arrow, a home button and a settings symbol (⚙) " +
                "in its header. On the players the ⚙ opens the settings that belong to them: Audio for songs, Radio for stations, Video for videos.\n\n" +
                "Long-press any setting to read what it does.",
            "The setup wizard" to "Each wizard page shows a few settings with their current value underneath. In the fixed header:\n" +
                "• Next checks the page (permissions granted, needed items set) and tells you what won't work if something is missing.\n" +
                "• Skip goes on without changes: defaults are used and everything stays reachable in Settings.\n" +
                "• Help opens this guide at the chapter of the page.\n\nYou can launch the wizard again from the last item of the main Settings page.",
        )),
        Chapter("perms", "Permissions", listOf(
            "Why permissions" to "Android asks you before an app may do certain things. vlc interface asks only for what its features need; each one " +
                "can be granted or taken back at any time in Android's settings for the app. Without one, only the feature that needs it stops.",
            "The list" to "• Notifications: the player's controls in the notification shade and the alarm notice.\n" +
                "• Music and videos: reading the songs and videos stored on the phone.\n" +
                "• Nearby devices: the names of Bluetooth outputs, their codec and the Bluetooth quality mode.\n" +
                "• Alarms and reminders: alarms that ring on the minute.\n" +
                "• Full-screen notifications: the ringing screen above the lock screen.\n" +
                "• Battery unrestricted: playback, radio saving and alarms keep running with the screen off.\n" +
                "• Calendar: your alarms shown in the phone's calendar.\n" +
                "• Location (optional): weather for where you are; otherwise the city you choose.\n" +
                "• Screen saver: the clock while the phone charges (chosen in Android's screen saver settings).",
            "Granted at install" to "Internet, network state, finding the NAS on the home network, keeping the phone awake while playing and " +
                "starting again after a reboot (to set the alarms back) need no question: Android grants them when the app is installed.",
        )),
        Chapter("library", "Library and files", listOf(
            "Your library" to "The Library gathers songs and videos from the phone and from every source you added. Folders are scanned automatically " +
                "(or only when you tap Rescan) and can be watched for changes.",
            "Album pictures" to "Albums without a picture can get one from MusicBrainz / Cover Art Archive, and artists a photo from Wikimedia. Pictures " +
                "are resized and saved next to the songs when a folder holds one album, else in the app's pictures folder.",
            "Removing files" to "Files can be deleted from the phone or the NAS (when the share allows writing) or only removed from the lists; " +
                "removed items can be brought back from Settings → Library and files.",
        )),
        Chapter("nas", "NAS and network", listOf(
            "Home servers" to "Add a NAS or share from Library → LAN: the app finds servers on the home network or takes an address by hand. SMB (Windows " +
                "shares, most NAS), NFS, SFTP, FTP, WebDAV and UPnP are supported. Passwords are kept encrypted on the phone.",
            "Away from home" to "Away from the home network the NAS is reached through your VPN (see the VPN chapter). The VPN-NAS button on the player " +
                "shows the state: NAS active (home), VPN - NAS active (through the VPN), VPN active (NAS not used), NAS unreach, or VPN-NAS off. " +
                "The symbol in front tells whether Wi-Fi or mobile data carries the connection.",
            "Mobile data" to "On mobile data the app asks before streaming from the NAS; caching ahead and ReplayGain scans wait for Wi-Fi unless you allow them.",
        )),
        Chapter("vpn", "VPN", listOf(
            "Why a VPN" to "Your NAS is at home; outside, a VPN (ZeroTier, Tailscale, WireGuard…) links the phone to the home network. Each NAS gets its " +
                "VPN address the first time it is seen through the VPN.",
            "Settings" to "• Use the NAS through the VPN.\n• Ask to start the VPN when it's off and a NAS song is wanted.\n• Which VPN app to open.\n" +
                "• Retry delay before a server counts as unreachable.",
        )),
        Chapter("playback", "Playback and gestures", listOf(
            "Gestures" to "On videos: slide vertically on the right half for volume, on the left half for brightness, horizontally to seek. Double tap " +
                "to skip. Each gesture can be switched off.",
            "Playback" to "Resume where you left off, keep playing in the background, the Up next list on the player, sleep timer and background work " +
                "are set here.",
        )),
        Chapter("audio", "Audio", listOf(
            "Sound" to "Start volume, preamp, ReplayGain (track / album), loudness and treble sliders with automatic loudness that fades as the volume rises.",
            "Per output" to "Each output (speaker, headphones, Bluetooth device) keeps its own preamp, balance and loudness start point. The sound " +
                "engine (AAudio) and the Bluetooth quality mode are chosen here too.",
        )),
        Chapter("video", "Video", listOf(
            "Subtitles and rotation" to "Preferred subtitle languages, subtitle style and per-file sync offsets; auto-rotate follows the phone or keeps " +
                "the app upright (videos still turn with their rotate button).",
            "Picture" to "In the video player: Audio and subtitles, hue and saturation, speed, lock and the settings symbol for this page.",
        )),
        Chapter("radio", "Radio", listOf(
            "Stations" to "Search and save stations; the radio player shows the song playing and its album picture when found.",
            "Saving songs" to "Songs heard on the radio can be saved to a folder, in the format you choose, kept while the buffer covers them. " +
                "Free-space limits stop saving before the phone fills up.",
        )),
        Chapter("look", "Look", listOf(
            "Colours and sizes" to "Accent colour (and a separate radio colour), button size, menu opacity, spacing and text size, pop-up opacity, " +
                "scroll bar width, shadows on player buttons.",
            "Settings order" to "The order of the links on the main Settings page is chosen on the Look page (▲ ▼, Reset order).",
        )),
        Chapter("alarm", "Alarm clock", listOf(
            "Alarms" to "Alarms ring once, on chosen weekdays or on specific dates. Each wakes you with phone music, a radio station (with a fallback) " +
                "or an alarm sound, rising to a maximum level, with snooze.",
            "Reliability" to "Alarm health checks the permissions and battery setting needed to ring on time; a test alarm rings in one minute. The " +
                "alarm stays reachable from a banner and its notification even after the app is closed.",
            "Clock and calendar" to "The clock (designs, colours, thickness, weather for your city) can replace the album picture and be the charging " +
                "screen saver. Alarms can be shown in your phone calendar.",
        )),
        Chapter("vis", "Visualisation", listOf(
            "Patterns" to "Colour clouds and mathematical patterns move with the music's chosen frequency range. Double tap to leave; slide along the " +
                "long side for the colours, along the short side for the brightness.",
        )),
        Chapter("backup", "Export and import", listOf(
            "Your setup in one file" to "Export saves the whole setup (Everything) or only the parts you tick: settings, NAS, VPN, Internet sources, " +
                "passwords (encrypted with a passphrase), radio favourites, playlists, alarms, subtitles, resume positions.",
            "Import" to "Import shows the parts found in the file; only the ticked ones are applied. Merge adds what's new and keeps yours, Replace " +
                "replaces the chosen parts. The current setup is backed up first.",
        )),
        Chapter("logs", "Logs", listOf(
            "What's logged" to "Errors, server connections, indexing and crashes are kept in logs you can read, export or clear in Settings → Logs, " +
                "together with the caches.",
        )),
    )

    fun chapter(id: String) = chapters.firstOrNull { it.id == id }

    /** Where the guide is: (chapter id, page); "" = contents. History for Back / Forward. */
    val history = mutableStateListOf<Pair<String, Int>>()
    var pos by mutableIntStateOf(-1)

    fun open(id: String, page: Int = 0) {
        while (history.size > pos + 1) history.removeAt(history.lastIndex)
        history.add(id to page); pos = history.lastIndex
    }
}

/** A help page: Close / Back / Forward in the header (Back / Forward move inside the guide). */
@Composable
fun HelpScreen(nav: Navigator, start: String) {
    val g = HelpGuide
    androidx.compose.runtime.remember(start) { if (g.pos < 0 || g.history.isEmpty()) g.open(start); 0 }
    val (cid, page) = g.history.getOrNull(g.pos) ?: (start to 0)
    val ch = g.chapter(cid)
    val scroll = rememberScrollState(); androidx.compose.runtime.LaunchedEffect(g.pos) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Scaled {
            Row(Modifier.fillMaxWidth().background(C.card).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeadBtn("✕") { nav.back() }
                HeadBtn("◀", g.pos > 0) { g.pos-- }
                HeadBtn("▶", g.pos < g.history.lastIndex) { g.pos++ }
                Text("Help", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (cid.isNotEmpty()) Text("Contents", color = accent(), fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { g.open("") }.padding(10.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ch == null) {
                Text("Contents", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                g.chapters.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).clickable { g.open(c.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${c.title}", color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("▶", color = accent(), fontSize = 18.sp)
                    }
                }
            } else {
                val p = page.coerceIn(0, ch.pages.lastIndex)
                Text("Chapter ${g.chapters.indexOf(ch) + 1} · ${ch.title} · page ${p + 1} of ${ch.pages.size}", color = C.muted, fontSize = 13.sp)
                Text(ch.pages[p].first, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(ch.pages[p].second, color = C.text, fontSize = 16.sp, lineHeight = 24.sp)
                Box(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    if (p > 0) Text("◀ Previous page", color = accent(), fontSize = 15.sp, modifier = Modifier.clickable { g.open(cid, p - 1) }.padding(8.dp))
                    Box(Modifier.weight(1f))
                    val ci = g.chapters.indexOf(ch)
                    if (p < ch.pages.lastIndex) Text("Next page ▶", color = accent(), fontSize = 15.sp, modifier = Modifier.clickable { g.open(cid, p + 1) }.padding(8.dp))
                    else if (ci < g.chapters.lastIndex) Text("${g.chapters[ci + 1].title} ▶", color = accent(), fontSize = 15.sp,
                        modifier = Modifier.clickable { g.open(g.chapters[ci + 1].id) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun HeadBtn(t: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(t, color = if (enabled) C.text else C.muted.copy(alpha = 0.4f), fontSize = 18.sp)
    }
}
