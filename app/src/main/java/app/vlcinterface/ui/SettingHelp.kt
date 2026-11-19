package app.vlcinterface.ui

/**
 * Explanations shown on a long-press of any setting. A setting without its own text here is explained by its short
 * description (the grey line under it), so every item gives an answer.
 */
object SettingHelp {
    private val texts: Map<String, String> = mapOf(
        // Playback & gestures
        "Volume swipe" to "In the video player, sliding up or down on the right half of the screen changes the volume.",
        "Brightness swipe" to "In the video player, sliding up or down on the left half of the screen changes the screen brightness (only for the player).",
        "Double-tap seek" to "In the video player, a double tap on the left or right edge jumps back or forward by this many seconds. Tap the value to change it.",
        "Hold for 2× speed" to "Keep a finger on the video: it plays twice as fast; let go and it goes back to normal speed.",
        "Resume where I left off" to "A video starts again where you stopped it last time (per file). Songs always start from the beginning.",
        "Keep audio in background" to "The sound keeps playing when you leave the app, with controls in the notification.",
        "Background playback" to "Android may stop an app in the background to save battery. 'Unrestricted' lets playlists go on while you use other apps.",
        // Audio
        "Loudness, Treble and Preamp sliders" to "Shows the Loudness, Treble and Preamp sliders on the audio player.",
        "Prevent clipping" to "Lowers the gain just enough that loud songs never distort (uses the ReplayGain peak values).",
        "Bluetooth quality mode" to "On a Bluetooth output: the app's own volume stays at 100 % and the device volume is used instead, and the sound is sent at the Bluetooth link's rate, so less conversion happens on the way.",
        "Remember last output per device" to "When a device connects again (headphones, Bluetooth speaker), the app picks it again as the sound output.",
        "Pause when output disconnects" to "Headphones unplugged or Bluetooth lost: playback pauses, so the phone's speaker never starts playing out loud.",
        // Radio & saved songs
        "Keep radio ready to save" to "To save a song from the radio, the app records the playing station in the background (a second connection, so double data). Wi-Fi only, always, or off.",
        "Format" to "How saved radio songs are stored: as the station sends them, or re-encoded as MP3 at the best variable quality (LAME V0).",
        "Free space limit" to "When the phone's free space is low, radio recordings may only use part of it. Below 2 GB free nothing is recorded at all.",
        "Share of free space" to "How much of the free space recordings may use when the limit above applies.",
        "Saved songs" to "The songs saved from the radio: play or delete them.",
        "Saved songs log" to "Each saved song, or the reason it couldn't be saved.",
        "MP3 encoder" to "The MP3 encoder bundled in the app (LAME), used for 'MP3 V0'.",
        "Radio buffer" to "The last minutes of the station are kept so a song that already started can still be saved; a lost connection that comes back within this time keeps the recording.",
        // Network & VPN
        "Allow SMB1" to "Lets the app reach very old NAS shares (SMB1). Fine on your home network; avoid it over the internet, it isn't encrypted.",
        "Spotify links" to "Song links can include Spotify when you add a free Spotify developer app's Client ID and Secret (no Spotify login, the keys stay on the phone).",
        "Status" to "Whether a VPN is running on the phone right now.",
        "Home NAS through the VPN" to "Away from home with the VPN on, your home servers are reached through the VPN; at home, directly.",
        "Ask to start the VPN" to "Away from home with the VPN off, the app offers to start it when you open a home server.",
        "VPN app" to "Which VPN app is opened by 'Start the VPN' (its saved login is used): automatic, ZeroTier, Tailscale or Android's settings.",
        "Home servers on the VPN" to "Your NAS addresses on the VPN, found by themselves when the VPN starts. 'Search for NAS now' looks again.",
        "VPN log" to "VPN on and off, servers found, switches between home network and VPN.",
        // Logs
        "All errors" to "Every failure from every log, newest first.",
        "Deleted files" to "Every file deleted from Settings, with its full path.",
        "Network" to "One log page per server (home network and internet), with the reason of every failure.",
        "Indexing" to "Folders indexed for search: songs found, or why not.",
        "ReplayGain" to "Every ReplayGain scan, and each file that had a problem.",
        "Crashes" to "App crashes, and why the app ended last time (as Android reports it).",
        "Sound engine" to "The app's own sound output: switches and problems.",
        "Radio pictures" to "For each song a station announced: the picture found, or what was searched.",
        "Player" to "Songs skipped because they couldn't be found or reached.",
        "Clear all caches" to "Deletes the radio buffer, songs being saved, radio pictures and temporary files. Settings, playlists, logs and saved songs stay.",
        // Video
        "Auto-download missing subtitles" to "When a film has no subtitles in your languages, the app downloads them from OpenSubtitles (needs your free account and API key).",
        "Preferred languages" to "Subtitle languages in order of preference, e.g. en, es.",
        "Auto-rotate" to "On: the app turns with the phone. Off: it stays upright (videos still turn with the rotate button).",
        // Library
        "Automatic scanning" to "The library is scanned by itself. Off: only when you tap Rescan.",
        "Watch folders for changes" to "New or removed files in your folders are noticed by themselves. Off: no watching in the background.",
        "Album pictures from the internet" to "Albums without a picture get one from MusicBrainz / Cover Art Archive (or an artist photo), saved in the album folder or the pictures folder.",
        "Pictures folder" to "Where pictures go for songs that aren't in a one-album folder.",
        "Phone music folders" to "Lets the app save album pictures next to the songs on the phone (allowed once, on the Music folder for example).",
        "Removed from lists" to "Files you took out of the lists with 'Remove from this list' (never deleted): show them again.",
        // Look
        "Scroll bar width" to "Width of the bar on the right of long lists; drag it to scroll fast.",
        "Button size" to "Size of every symbol button and its tap area, everywhere in the app.",
        "Songs list horizontal autoscroll" to "Speed at which long song rows glide to their end and back in Library → Songs. 0 = they stay still.",
        "Up next songs on the player" to "How many of the next songs the player shows under the controls.",
        "Pop-up opacity" to "How see-through pop-up windows are.",
        "Shadow on player buttons" to "A faint grey shadow on the players' buttons and slider knobs.",
        "Menu opacity" to "How see-through menus and search results are (separate from pop-ups).",
        "Menu row spacing" to "How close the rows of menus and search results are.",
        "Menu text" to "Text size in menus and search results.",
        "Colour for" to "The app's accent colour: one for files, one for the radio. Long-press a colour circle to edit it, '+' adds one (up to 5).",
        "Order of the Settings links" to "The order of the links on the main Settings page. Move a link with ▲ ▼; Reset puts back the original order.",
        // Export
        "Include saved passwords" to "The export file also holds the passwords of your servers. Keep that file private.",
        "Alarm clock" to "Alarms, ringing, snooze, wake check and the clock's look.",
    )

    fun text(title: String, hint: String): String {
        texts[title]?.let { return it }
        texts.entries.firstOrNull { title.startsWith(it.key) }?.let { return it.value }
        return hint.ifEmpty { "No further explanation for this setting." }
    }
}
