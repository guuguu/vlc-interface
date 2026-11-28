package app.vlcinterface.ui

/**
 * Explanations shown on a long-press of any setting (and by the Helpers): always deeper than the grey line under the
 * setting — what it does, what changes, when to use it, what it costs, and its default.
 */
object SettingHelp {
    private val texts: Map<String, String> = mapOf(
        // ---------------------------------------------------------------- Playback & gestures
        "Volume swipe" to "In the video player, slide a finger up or down on the right half of the screen to change the volume, without reaching for the phone's buttons.\n\n" +
            "On: the right half reacts to vertical slides; a short bar shows the level. Off: the right half ignores slides, useful if you often touch it by accident while holding the phone.\n\n" +
            "It changes the media volume, the same one as the volume keys. Default: on.",
        "Brightness swipe" to "In the video player, slide up or down on the left half of the screen to change the screen brightness while the video plays.\n\n" +
            "The change is only for the player: leaving it brings back the phone's own brightness. Off: the left half ignores slides.\n\n" +
            "Lower brightness also saves battery during long videos. Default: on.",
        "Double-tap seek" to "In the video player, a double tap near the left or right edge jumps back or forward by the number of seconds shown.\n\n" +
            "Tap the value to choose the jump (for example 10 s for films, 5 s for clips). Several double taps in a row add up.\n\n" +
            "The middle of the screen is never used for seeking: a single tap there shows the controls, a double tap opens picture-in-picture. Default: 10 s.",
        "Resume where I left off" to "A video opened again starts where you stopped it last time; the position is kept per file, on the phone, together with its audio and subtitle choices.\n\n" +
            "Songs always start from their beginning (an album or playlist keeps its own place instead). Off: every video starts from 0.\n\n" +
            "Positions can be exported with the setup (Resume positions). Default: on.",
        "Keep audio in background" to "Music, radio and a video's sound go on when you leave the app or lock the screen, with Play / Pause / Next in the notification and on the lock screen.\n\n" +
            "Off: leaving the app pauses playback. Background playback uses a little battery for the sound and, for network songs, the Wi-Fi or mobile data that streams them.\n\n" +
            "Default: on.",
        "Background playback" to "Android may stop apps in the background to save battery, which can cut a playlist, a radio recording or an alarm preparation while you use other apps or the screen is off.\n\n" +
            "'Unrestricted' tells Android to leave the app running. The app only works in the background while it plays, records or prepares an alarm, so the cost is small.\n\n" +
            "Tap it to open Android's battery page for the app.",
        // ---------------------------------------------------------------- Audio
        "Sound check" to "Measures what really happens to the song playing, on this phone, instead of guessing. Start it while a song plays; " +
            "it takes about 15 s. Songs, videos and radio stations (for live radio, decoding isn't compared: a live stream can't be read twice).\n\n" +
            "Decoding: 10 s of what libVLC hands the app's sound engine are compared, sample by sample, with the same passage " +
            "decoded by Android's own decoder. 'Identical' means the file arrives exactly as it is.\n\n" +
            "Sound engine: what the engine sends to Android is compared with what it received — bit-identical, only a level change " +
            "(ReplayGain, preamp, volume), or more (tone, treble, limiter); and whether the sample rate is converted, and by whom.\n\n" +
            "Output: the output, the stream's rate and format, the Bluetooth codec and link rate, Bluetooth quality mode, the phone " +
            "volume. Share or copy the result to compare with other players.",
        "Music folders with write access" to "The phone folders the app may write ReplayGain tags into, given with Android's own folder picker: the app gets those folders and their subfolders only — not mails, photos, messages or anything else. Add one with \"+ Add a music folder…\" (pick your music folder, then \"Use this folder\" and \"Allow\"), or answer the question that comes the first time a phone folder gets ReplayGain or a song of it plays: Yes (Android's picker opens on that folder), No (never asked again for it — it can still be added here), or Choose location (another music folder from a list of those without access, or browsed in the app). Remove takes the access back at once.",
        "Treble band" to "The one filter the Treble slider on the players drives (double tap the Treble slider to come here).\n\n" +
            "Shelf: everything above the frequency is raised (Treble +) or lowered (Treble −) — like an amplifier's treble knob. " +
            "Peak: only a band around the frequency is raised or lowered — one band of a parametric equalizer.\n\n" +
            "Frequency 1–16 kHz (type it, slide it, or drag on the curve). Width: the shelf's slope (smaller = gentler rise) or " +
            "the peak's Q (smaller = wider band). At slider end: how many dB the slider's ±5 means.\n\n" +
            "Flatten at volume: the correction shrinks in proportion to the volume — all of it at volume 0, none at the volume set " +
            "(e.g. 150 %: two thirds left at 50 %, a third at 100 %). Off (0): the whole correction at every volume. The white curve " +
            "shows Treble +5 at the volume now.\n\n" +
            "The curve shows the result: solid line Treble +5, dashed −5. One setting for all songs and stations; each song's own " +
            "Treble value works on top of it. Works with the app's own sound output. Default: Shelf, 4000 Hz, slope 0.6, ±10 dB.",
        "Start volume" to "The phone's media volume the app sets before any sound: when the app starts — always after \"Restart the app\"; after Exit, unless you open it again within the \"Reset volume after\" time; not when Android restarted it after a short Bluetooth drop, and after the quiet time set in \"Reset volume after\". It's set on the output in use at that moment (speaker, headphones or the Bluetooth device).\n\n" +
            "Default: 40 %.",
        "Start volume on a new output" to "On (default): whenever the sound moves to another output device — Bluetooth headphones or speaker connecting, wired headphones plugged in, an output picked in the Sound output sheet — the volume is set to the Start volume on that device, so it never starts loud there. A short drop of the same device that comes back (a Bluetooth hiccup) doesn't count: it gets its own volume back. Off: each device keeps the volume it had. Written to the Player log each time.",
        "Reset volume after" to "How many minutes with nothing playing (paused, stopped, or the app idle) before the volume goes back to the Start volume. It happens right before the next song, station or video starts — not while the phone is quiet, so other apps' sound isn't changed. The Preamp (everything) is a setting and stays as set; the app's own volume on a device it picked goes back to 100 %. An alarm never triggers it (it sets its own volume).\n\n" +
            "Typed with the digit pad, 0–999 minutes. 0 = never (the start volume only when the app starts). Default: 30 minutes.",
        "Loudness, Treble and Preamp sliders" to "Shows three sliders on the audio and radio players: Loudness (more bass and treble at low volume, like a hi-fi's loudness button), Treble, and Preamp (overall gain before the volume).\n\n" +
            "Off: the sliders are hidden, but their last values still apply. Loudness fades away by itself as the volume goes up, so loud listening is never boomy.\n\n" +
            "Each output (speaker, headphones, Bluetooth) keeps its own values. Default: shown.",
        "Songs without ReplayGain" to "The level a song is played at when it has no ReplayGain at all, from −12 dB to 0 dB in half-dB steps (default −3 dB). It's added to the preamps and the player's Preamp slider as they are.\n\n" +
            "It lasts until the song's ReplayGain is measured (the automatic scan does the playing song first); then its own value is put on, once. Over the VPN, where songs aren't scanned, it's 3 dB lower still.\n\n" +
            "0 dB plays such songs at the same level as the rest; lower values keep loud untagged songs from jumping out. A change applies at once to a song playing without ReplayGain.",
        "Prevent clipping" to "Loud songs boosted by Preamp or ReplayGain can go beyond the maximum and distort ('clip'). This lowers the gain just enough that the song's loudest peak stays clean.\n\n" +
            "It uses the peak values stored in the song's tags (written by ReplayGain scans). Songs without peak tags play as they are.\n\n" +
            "No battery or data cost. Default: on.",
        "Bluetooth quality mode" to "On a Bluetooth output, the app keeps its own volume at 100 % and uses the device's volume instead, and sends the sound at the Bluetooth link's sample rate.\n\n" +
            "That avoids one volume scaling and one rate conversion, so the sound reaches the codec (SBC, AAC, LDAC…) as clean as possible. The volume keys then move the device volume.\n\n" +
            "Only for Bluetooth outputs; speaker and wired headphones are unchanged. Default: off.",
        "Remember last output per device" to "When headphones or a Bluetooth speaker connect again, the app chooses them as the sound output by itself, with their own preamp, balance and loudness — unless you picked another output in the Sound output sheet: that pick stays until its device is disconnected or you pick another one.\n\n" +
            "Off: the app stays on the output you chose last, whatever connects.\n\nDefault: on.",
        "Pause when output disconnects" to "When headphones are unplugged or a Bluetooth device goes away, playback pauses instead of going on through the phone's speaker.\n\n" +
            "Off: the sound moves to the phone's speaker and keeps playing — fine at home, embarrassing on a bus.\n\nDefault: on.",
        // ---------------------------------------------------------------- Radio & saved songs
        "Keep radio ready to save" to "To save a song you hear on the radio — even one that already started — the app keeps a recording of the playing station in the background.\n\n" +
            "That is a second connection to the station, so twice the data. 'Wi-Fi only' records on Wi-Fi, 'Always' also on mobile data, 'Off' never (then songs can't be saved).\n\n" +
            "Recordings live in the radio buffer and are deleted as time passes. Default: Wi-Fi only.",
        "Saving root" to "The folder where saved radio songs go. Inside it, 'raw' holds songs as the station sends them, 'mp3' the re-encoded copies.\n\n" +
            "By default it is the root of your phone music library, so saved songs also appear in the Library. Tap to choose another folder (for example on the SD card).\n\n" +
            "Space used depends on the format and how many songs you save.",
        "Use the library root again" to "Goes back to saving radio songs in the root of your phone music library, forgetting the folder you chose.\n\n" +
            "Songs already saved stay where they are.",
        "Format" to "How saved radio songs are stored. 'Original' keeps the station's own stream (FLAC, AAC, OGG, MP3…) untouched — best quality, any size. 'MP3' re-encodes with LAME at V0 (the best variable quality).\n\n" +
            "MP3 plays everywhere and is smaller than FLAC, but re-encoding a lossy stream loses a little quality.\n\nDefault: MP3.",
        "Free space limit" to "Protects the phone from filling up: when free space is low, radio recordings may only use the share chosen below. Under 2 GB free nothing is recorded at all.\n\n" +
            "Off: recordings use what they need (the buffer length still limits them).",
        "Share of free space" to "When the free-space limit applies, the part of the remaining free space that radio recordings (kept minutes and songs being saved) may use.\n\n" +
            "A smaller share keeps more room for photos and apps; a bigger one allows longer buffers.",
        "Saved songs" to "The list of songs saved from the radio: play them, see where they are, or delete them (from the phone too).\n\n" +
            "They're also in the Library under their folder.",
        "Saved songs log" to "Each save attempt: the song saved and where, or why it couldn't be saved (not enough buffer, no space, station changed…).\n\n" +
            "Useful when a save seems to be missing.",
        "MP3 encoder" to "The LAME MP3 encoder bundled with the app, used when the format is MP3. It runs on the phone, no internet needed.\n\n" +
            "LAME is free software (LGPL), included unchanged.",
        "Address: Auto" to "The app picks this location's address by itself, every time it connects:\n\n" +
            "• at home (the phone on the same network as the server): the LAN address;\n" +
            "• on another network where one of the other saved addresses is reachable: that one;\n" +
            "• away from home with the VPN on: the VPN address;\n" +
            "• otherwise: the LAN address.\n\n" +
            "It follows Wi-Fi and VPN changes on its own. \"In use\" under the addresses says which one is used now. Tap an address instead to always use that one.",
        "Address: +" to "Adds another address for the same server by hand — an IP or a name, e.g. its address on ZeroTier / Tailscale or a second LAN address. " +
            "Its folders, files and index stay the same; only the way to reach it changes.\n\n" +
            "The new address joins the list on the right (tap it to always use it; Auto may pick it too). Long-press an added address to remove it.",
        "Helpers" to "Short pop-ups that explain a setting the first time you change it in each session (and what an alarm will do after you choose its days). " +
            "Off: no pop-ups at all. On: below it, one switch per settings page (Alarm page, Audio, Playback & gestures, Radio, Video, Look, Library & files, Visualisation, Clock aspect, File associations) to keep them for some pages only.\n\n" +
            "A long press on any setting always shows its explanation, whatever is chosen here.",
        "Touch guard" to "Protects the players' main buttons from accidental touches — play / pause, ⏮ ⏭, −10 / +10, Sleep, ♪ Identify, the sine button, the clock and visualisation buttons, Random / Repeat / RGain and the floating buttons, on the song, radio and video players.\n\n" +
            "A button acts only when the finger stayed on it at least this long without sliding: a brush, a palm, or a touch that turns into a scroll does nothing. The same button pressed again within 0.4 s is ignored, so nothing happens twice.\n\n" +
            "60 ms (default) is shorter than a normal tap, so taps work as always while brushes are ignored; raise it if accidental presses still happen. Off: a plain tap, as before. Long presses and drags work as always.",
        "Radio buffer" to "How many minutes of the playing station are kept. A song that started up to this long ago can still be saved whole, and a connection lost and back within this time keeps the recording going.\n\n" +
            "Longer buffers use more phone storage (about 1 MB per minute for MP3 stations, much more for FLAC). The buffer is deleted when you stop the radio or clear caches.",
        // ---------------------------------------------------------------- Network & VPN
        "Allow SMB1" to "SMB1 is the oldest version of Windows file sharing; some old NAS and routers only speak it. On: the app can reach them, through its own SMB bridge when libVLC can't.\n\n" +
            "SMB1 isn't encrypted and has known weaknesses: fine on your home network, avoid it over the internet. Off: SMB1-only servers can't be opened.\n\nDefault: off.",
        "Spotify links" to "Song links (the ⋯ menu of a song) can include Spotify when you add the Client ID and Secret of a free Spotify developer app.\n\n" +
            "No Spotify login is needed and the keys stay on the phone (exported only with passwords). Without keys, links to other services still work.",
        "VPN on" to "Whether a VPN is running on the phone right now, and which app runs it. It updates by itself.",
        "Home NAS through the VPN" to "Away from home with the VPN on, your home servers are reached through the VPN; at home they're reached directly, which is faster.\n\n" +
            "The app learns each server's VPN address the first time it is seen through the VPN. Off: home servers are only used at home.\n\n" +
            "Data: songs stream through the VPN over mobile data when away. Default: on.",
        "Ask to start the VPN" to "Away from home with the VPN off, when you open a home server, the app offers to start the VPN (it opens the VPN app chosen below, with its saved login).\n\n" +
            "'Away from home' means the phone isn't on the network of any address stored for a home server. Off: no question; home servers just aren't reachable away.",
        "VPN app" to "Which app 'Start the VPN' opens: automatic (the one installed), ZeroTier, Tailscale, or Android's VPN settings.\n\n" +
            "The VPN app keeps its own login; the app never sees it.",
        "Home servers on the VPN" to "The VPN addresses of your NAS, found by themselves when the VPN starts. 'Search for NAS now' looks again, for example after you add a server or change the VPN.\n\n" +
            "If a server isn't found, its home address is tried through the VPN too.",
        "VPN log" to "Everything the VPN link did: VPN on and off, servers found, switches between home network and VPN, and failures with their reason.",
        // ---------------------------------------------------------------- Logs
        "All errors" to "Every failure from every log in one list, newest first, with the page it came from. Start here when something didn't work.",
        "Deleted files" to "Every file deleted from the app, with its full path and when — to check what went away, or find a file to restore from a backup.",
        "Network" to "One log page per server (home network and internet): connections, listings, plays, and the reason of every failure (password, unreachable, protocol…).",
        "Indexing" to "Folders indexed for search: how many songs and videos were found in each, every automatic start (and why it waits: server not answering, battery, VPN on, background work paused, a ReplayGain scan running), pauses for ReplayGain, interrupted runs resumed, and files that couldn't be read in the background.",
        "ReplayGain" to "Every ReplayGain scan (loudness measurement): files done, and each file that had a problem, with the reason.",
        "Crashes" to "App crashes with their technical details, and why the app ended last time as Android reports it (killed for memory, battery, crash…). Useful to report a problem.",
        "Sound engine" to "The app's own sound output (AAudio): output switches, sample rates and problems, such as a device that refused a format.",
        "Radio pictures" to "For each song a station announced: the picture found and from where, or what was searched without result.",
        "Player" to "Songs skipped because they couldn't be found or reached, phone files libVLC couldn't open (with the way it tried: path or file handle), and every time the sound output picked in the app was released, with the reason.",
        "Clear all caches" to "Deletes the radio buffer, songs being saved, radio pictures and temporary files, freeing their space now.\n\n" +
            "Settings, playlists, logs, saved songs and your files stay. Caches fill up again as you use the app.",
        // ---------------------------------------------------------------- Video
        "Auto-download missing subtitles" to "When a film has no subtitles in your preferred languages, the app searches OpenSubtitles and downloads the best match, saved next to the film.\n\n" +
            "It needs your free OpenSubtitles account and API key, and uses a little data per film. Off: only subtitles already in or next to the film are used.",
        "Preferred languages" to "Subtitle (and audio track) languages in order of preference, as short codes separated by commas, for example 'en, es'.\n\n" +
            "The first language found in a film is chosen; empty means the phone's language.",
        "Auto-rotate" to "On: the app turns with the phone, upright or sideways. Off: the app stays upright whatever the phone does.\n\n" +
            "Videos can always be turned with their own rotate button. Default: on.",
        // ---------------------------------------------------------------- Library
        "All files access (like VLC)" to "Android normally lets the app see only the files it lists as media. Some folders are left out of that list on purpose — folders with a \".nomedia\" file (often messaging apps' audio and voice notes), or files Android didn't index — so VLC, which has \"All files access\", can show more music.\n\n" +
            "Tap the switch to open Android's page for this app and turn \"Allow access to manage all files\" on (or off again). With it on, the app reads the phone's storage itself and adds those files to Library → Songs and Videos and to searches. Hidden \".folders\", the apps' private data and unreadable files (encrypted downloads of streaming apps) stay out.\n\n" +
            "The places you added to the Library still decide which of Android's own media folders are shown; Library → Songs says when there are songs in places not added.",
        "Index again" to "Pinned network folders (★) are indexed so their songs and videos can be searched without reading the NAS each time. This says when an indexed folder is read again to catch new, changed or removed files.\n\n" +
            "Never: only the first time, and when you tap 🔍 Index or ↻ Rescan. Every N days (default 4): each home-network folder is read again N days after its last indexing, by itself. When a new file appears: a folder is read again as soon as the app sees a file there that isn't in the index — when you open the folder or play something from it.\n\n" +
            "Indexing always runs in the background at low priority, several folders at once, pauses for ReplayGain scans and resumes after. Tags already read come from the cache, so reading again is quick. A file played from a folder that isn't indexed yet gets that folder indexed at once, whatever is chosen here.",
        "Automatic scanning" to "The library looks for new, changed and removed files by itself when the app starts and when sources come online.\n\n" +
            "Off: only when you tap Rescan — saves battery and NAS traffic with very large libraries.\n\nDefault: on.",
        "Watch folders for changes" to "While the app runs, phone folders are watched so new or removed files show up at once.\n\n" +
            "Off: changes are seen at the next scan. The cost is very small. Default: on.",
        "Album pictures from the internet" to "Albums without a picture get one from MusicBrainz / Cover Art Archive, and artists a photo from Wikimedia, when the album has none.\n\n" +
            "Pictures are resized for the phone and saved as cover.jpg / artist.jpg in the album folder when it holds one album, else in the pictures folder. Uses a little data once per album. Default: on.",
        "Pictures folder" to "Where pictures go for songs that aren't in a one-album folder: one file per picture, named by a unique id and tied to its song, album and artist.\n\n" +
            "Deleting it only means pictures are downloaded again when needed.",
        "Phone music folders" to "Android only lets an app write next to your songs after you allow a folder once (the Music folder, for example).\n\n" +
            "Allowed: album pictures are saved next to the songs, and other music apps see them too. Not allowed: pictures stay in the app's pictures folder.",
        "Removed from lists" to "Files and folders you took out of the lists with 'Remove from this list'. They were never deleted: open this to show them in the lists again.",
        "Find media folders" to "Searches the phone's storage — internal storage, the SD card, USB storage and Downloads — for folders that hold songs or videos.\n\n" +
            "The usual media folders on internal storage are in the Library already. Any other folder found is listed with a tick box: only the ones you tick are added. Folders already in the Library are shown as added and stay as they are (nothing is overwritten or added twice).\n\n" +
            "Use it after inserting an SD card or USB stick, or when songs you copied don't appear. It only reads Android's media index: no data, almost no battery.",
        "Launch the setup wizard" to "Opens the setup wizard again: the permissions page, then a page for each group of settings, with each setting's current value underneath.\n\n" +
            "Nothing changes unless you change it; Skip leaves a page as it is.",
        // ---------------------------------------------------------------- Look
        "Scroll bar width" to "Width of the bar on the right of long lists, in pixels. Drag the bar to scroll fast through thousands of songs.\n\nWider is easier to grab, narrower hides less of the rows.",
        "Button size" to "Size of every symbol button and its tap area, everywhere in the app. Bigger is easier to hit (in a car, with gloves); smaller shows more on screen.",
        "Automatic ReplayGain for folders" to "A song without any ReplayGain plays at Audio → ReplayGain → \"Songs without ReplayGain\" (default 3 dB under the current level) until it is measured; then its own value is put on once — one change, no going up and down. A song whose ReplayGain is known starts at its value.\n\nOn (default): when a song without any ReplayGain starts playing from the phone or from your network at home, every song of its album that has none is measured in the background (its folder, and every disc folder of it when the album is split in CD1 / CD2 / Disc 1 …) — no question, no notification, nothing to tap. Each song gets its own track gain (ReplayGain per song), written into the file's tags — on your NAS and on the phone. The song playing gets its tags as soon as it ends (a file can't be changed while it plays). Phone files: written in the music folders the app has write access to. The first time a phone folder gets ReplayGain (or a song of it plays), the app asks for its music folder: Yes (Android's folder picker opens on it — the app gets that folder and its subfolders only), Choose location (another music folder), or No; \"No\" is kept: that folder is never asked again (it can still be added by hand under Music folders with write access). Without access the values are kept in the app; a read-only NAS share keeps them in the app too. The song playing uses its value as soon as it's measured. When the whole album is done a short pop-up shows for 5 seconds: how many songs were scanned and each one's new gain. Songs that already have ReplayGain are left as they are. Network songs reached through the VPN (away from home) still ask first, as scanning over the VPN is slow. Off: the question for every folder, as before.",
        "Songs list autoscroll" to "Long rows in Library → Songs (artist – album – title) that don't fit glide continuously one way, starting over seamlessly, so you can read them whole. Rows that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still (cut with …).",
        "Folder lists autoscroll" to "Long folder and song names in the phone and network folder lists glide continuously one way when they don't fit; names that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still (cut with …).",
        "Video lists autoscroll" to "Long video names in the phone and network folder lists glide continuously one way when they don't fit; names that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still (cut with …).",
        "Favourite stations autoscroll" to "Long station names in your favourite stations (in the radio player and in Library → Radio) glide continuously one way when they don't fit; names that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still (cut with …).",
        "Find stations autoscroll" to "Long station names in the Find stations results and in station lists from playlists glide continuously one way when they don't fit; names that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still (cut with …).",
        "Up next autoscroll" to "Long names in the players' Up next lists glide continuously one way when they don't fit; names that fit stay still. This is their speed: 100 % normal, up to 300 %; 0 keeps them still.",
        "Player titles autoscroll" to "The title on the players — the song, the station and what it plays, the video — is on one line at full size; when it's too long it glides continuously one way, and a title that fits stays still and centred. This is its speed: 100 % normal, up to 300 %; 0 keeps it still (cut with …).",
        "Up next songs on the player" to "How many of the coming songs (or videos, on the video player) the player shows under its controls (0–20). Tap one to jump to it; hold its ≡ to move it.",
        "Pop-up opacity" to "How see-through pop-up windows are. More see-through shows what's behind; fully opaque is easier to read.",
        "Floating button size" to "Size of the round play / pause button that floats over the audio and radio players (always, or only once their buttons have scrolled out of view — see Floating play / pause button).\n\n" +
            "Bigger is easier to hit without looking; smaller covers less of the list. Drag the button anywhere on the player — its place is kept. Default: 64 dp.",
        "Lava amplitude" to "Lava flow only: how far the lava blobs wander and how much they swell with the bass, separate from the bars' " +
            "amplitude. Small: the blobs stay near the middle and calm; big: they roam to the edges and swell strongly on the bass. " +
            "From ×0.4 to ×2.5; default ×1. The preview shows it live while you hold the slider still.",
        "Amplitude" to "How much the spectrum and analyser bars jump between the quiet and the loud parts of the music. " +
            "Small: the bars stay near a steady height and only ripple with the music; big: they swing from almost nothing up to the " +
            "full frame on every beat. In Lava flow it's the bars' amplitude (the lava has its own slider). Sensitivity then lifts the whole movement higher or lower. From ×0.4 to ×2.5; default ×1. " +
            "The preview shows it live while you hold the slider still.",
        "Full-screen brightness" to "How bright the screen is while the visualisation fills the screen. " +
            "Phone's own: the phone's usual brightness. Otherwise the level set here, from 1 % (a dim night light) to 100 %. " +
            "Sliding up or down on the left half of the full-screen visualisation changes it too, and that level is kept for next time (the right half sets the sound volume). " +
            "Leaving full screen always gives the phone its own brightness back. Default: Phone's own.",
        "Sensitivity" to "How strongly the spectrum and analyser pictures react. Higher: the bars, rows, rays and wave reach the edges of " +
            "the frame (fill it) on the loud parts; lower: they stay calmer and rarely touch the edges. Quiet passages are lifted too. " +
            "From ×0.5 to ×3; default ×1.3. The preview shows it live while you hold the slider still.",
        "Bar thickness" to "How thick each bar of the spectrum and analyser pictures is (centred spectrum, LED bars, light bars, radial). The bars always fill the whole width, so thicker bars means fewer " +
            "of them, each covering a wider slice of the sound: thin bars show more detail, thick bars a bolder beat. From 3 to 40 dp; default 10 dp.",
        "OpenSubtitles account" to "Subtitles are searched and downloaded from OpenSubtitles.com. It needs three things from a free account there:\n\n" +
            "1. Account: on opensubtitles.com choose Register, confirm the e-mail it sends, then log in.\n" +
            "2. API key: open your profile → API consumers → New consumer; any name (for example 'vlc interface'); " +
            "copy the long key it shows and paste it into 'API key' here.\n" +
            "3. Username and password: the ones you log in with on the site (needed to download; searching works with the key alone).\n\n" +
            "The site limits downloads per day (more with a VIP account); when the limit is reached the app says so. " +
            "The key and the password are kept on the phone and left out of exported setups unless you give a passphrase.\n\n" +
            "With 'Auto-download missing subtitles' on, a film with none in your preferred languages gets one by itself a few seconds after it starts.",
        "Video decoding" to "How videos are turned into pictures.\n\n" +
            "Automatic: chosen for each video from its encoding — the phone's hardware decoder (fast, saves battery) when it can decode that codec " +
            "at that size, otherwise the app's own software decoder; if the hardware fails on a video, it switches to software at once and keeps that " +
            "for the file next time. "  +
            "Hardware: only the phone's decoder — a file it can't handle shows an error instead of playing slowly. " +
            "Software: only the app's decoder — try it when a video shows green, grey or garbled pictures, blocks or stripes on this phone; " +
            "it uses more battery and very large videos (4K) may stutter.\n\n" +
            "Changing it reopens the playing video at the same spot. Default: Automatic.",
        "Deinterlacing" to "Old TV recordings, DVDs and some camcorder videos are 'interlaced': moving things show thin comb-like lines.\n\n" +
            "Automatic: chosen for each video from its encoding — on for TV-style encodings (MPEG-2, DV, TV recordings .ts / .m2ts / .vob / .mpg " +
            "at TV sizes), off for the rest so they keep the fastest hardware path; learnt per file. On: every video (only fields marked interlaced " +
            "are smoothed, yadif 2×: full motion kept). Off: shown as they are.\n\n" +
            "It uses more battery while such a video plays. Changing it reopens the playing video at the same spot. Default: Automatic.",
        "Visualisation instead of album pictures" to "The song and / or radio player show the visualisation, always, in the place of the album picture or station logo — " +
            "with the patterns, colours and frequencies chosen on this page.\n\n" +
            "Off: the album picture as usual (the visualisation still fills the place when no picture is found). Songs: only the song player. " +
            "Radio: only the radio player. All: the two players.\n\n" +
            "It keeps the picture's place and rules: scrolls and hides with the page like the picture, and a long press opens this page. " +
            "The clock, when on, still takes that place. The visualisation uses a little more battery than a still picture. Default: Off.",
        "Floating play / pause button" to "A round play / pause button floating over the audio and radio players, that you can drag anywhere (its place is kept). It's also on the full-screen visualisation and the full-screen clock (unless Off). A smaller ⏭ button is stitched to its lower right (2/3 of its size, same colour): it skips to the next item of the queue; drag either one to move both — the pair always stays whole on the screen.\n\n" +
            "'Always': it's always there, handy when the album picture or clock takes the screen. 'When the buttons scroll away': it appears only while the player's own buttons are out of view. 'Off': never shown.\n\nDefault: Always.",
        "Cover hidden for" to "With the phone upright and the album picture shown on the player, scrolling the player hides the picture completely, so the list and buttons get its room.\n\n" +
            "It comes back this many seconds after your last action; every scroll, touch or button press starts the count again, and it never comes back while a menu, pop-up or settings window is open. Sideways the picture stays on the left. The clock has its own time.\n\nDefault: 10 s.",
        "Floating button colour" to "The colour of the round play / pause button that floats over the players, separate from the accent colour so it stands out over album pictures and lists.\n\n" +
            "'+ Create' opens the colour editor; the ▶ / ❚❚ symbol turns dark or white by itself to stay readable. Default: the accent colour.",
        "Floating button transparency" to "How see-through the floating play / pause button is: 0 % is solid, higher lets the list show through it.\n\n" +
            "It stays fully usable at any transparency. Default: 15 %.",
        "Alarms shown under the clock" to "How many alarm rows the box under the clock on the players shows at once (1–10, default 5). With more alarms, the list scrolls inside the box.\n\n" +
            "The alarms are listed in ringing order: the next one to ring on top (a snoozed one at its snooze time), then the following ones; switched-off alarms come last. So with 2, you always see the next two alarms.",
        "Alarm rows under the clock" to "Height of each alarm row in the list under the clock on the player (and the alarms list), as a share of the original rows.\n\n" +
            "50 % fits twice as many alarms in the same space; the list shows every alarm and scrolls on its own when they don't all fit. Low rows show the name and repetition on one line; from 75 % on two.\n\n" +
            "The wide switch of each row shrinks with it but stays easy to hit. Default: 50 %.",
        "Hide the clock while scrolling" to "Upright player with the clock shown. On: the clock is the top of the page and scrolls up with it; once out of view it's taken away (the page doesn't jump) and comes back after 'Clock hidden for' seconds from your last action.\n\n" +
            "Off: the clock is pinned above the page and always stays; only the page scrolls under it. Sideways the clock always stays on the left. Default: on.",
        "Hide the album picture while scrolling" to "Upright player with the album picture shown. On: the picture scrolls up with the page; once out of view it's taken away (no jump) and comes back after 'Cover hidden for' seconds from your last action.\n\n" +
            "Off: the picture just scrolls with the page and is never taken away. Sideways it always stays on the left. Default: on.",
        "Player rows spacing" to "The empty space between the rows of the song and radio players: title block, VPN-NAS button and badges, position bar and its times, player buttons and the rest.\n\n" +
            "Small gaps keep everything up to the player buttons on screen at once; bigger gaps are airier. The rows themselves stay slim. Default: 4 dp.",
        "Vertical spacing" to "The empty space between and inside the rows of the settings pages, the setup wizard and the help guide: between a setting's title, the text under it and its slider or buttons, between rows, and around section titles and graphical items.\n\n" +
            "Lower fits more settings on screen; higher is airier. Touch areas stay easy to hit. Default: 85 %.",
        "Text size" to "The size of all the text on the settings pages, the setup wizard, the help guide and the helper pop-ups — titles stay a step bigger than the text under them.\n\n" +
            "The players and the clock aren't affected: their text fits itself to the room. Default: 100 %.",
        "Clock on the video" to "The time, digital, in a corner of the video picture — always while a video plays, upright and sideways — full screen it hides while the player buttons are shown, but stays while the controls are locked (not in picture-in-picture either). It follows the clock's 24-hour setting (Clock aspect).\n\n" +
            "Clock size: 50 %–250 % of its normal size. Clock opacity: how solid it is — lower lets the film show through. Clock corner: top left, top right, bottom left or bottom right (bottom corners stay above the position bar). Digits colour: any colour, made in the colour editor (+ Create); White puts back the default. Clock shadow: a grey shadow under the digits, so they stay readable on bright scenes — Shadow intensity (10–100 %: from a faint haze to solid grey) and Shadow width (1–12 dp: how far it falls and spreads).\n\n" +
            "Default: shown, 100 %, 80 %, top left, white digits, grey shadow at 70 % and 3 dp.",
        "List row spacing" to "The space between the items of every list and queue in the app, one value for all: the Queue page, Up next on the song and video players, playlists, phone and network folders, Library → Songs and Videos, the phone's folder list, radio favourites and search results.\n\n" +
            "5 % packs the rows as close as their text allows — the rows' small buttons (✕, ⋮, ≡) and the line spacing of their text shrink too, so the queue and playlists fit many more items; 160 % gives roomy rows that are easier to tap. Text sizes don't change. Default: 70 % (the rows used to be at 100 %).",
        "Order of the player rows" to "The order of the rows on the song player, the radio player and the video player (upright, the rows under the picture), each with its own order. All: the song player's order for the radio player too, and the video player's Title, Play buttons and Up next follow it. The song / radio rows: picture (or clock with alarms), title block, position bar, player buttons, tone sliders, 'Playing from', 'Play next…', extra buttons, and Up next (radio: favourites and search).\n\n" +
            "Drag a row by its ≡ (or long-press it) to move it; 'Reset order' puts back the original order. Sideways the picture and title stay on the left side and the other rows keep their order on the right. " +
            "The album picture hides while scrolling only when it's the first row.",
        "Clock hidden for" to "With the phone upright and the clock shown on the player, scrolling the player hides the clock completely, so the list and buttons get its room.\n\n" +
            "The clock comes back this many seconds after your last action; every scroll, touch or button press starts the count again, and it never comes back while a menu, pop-up or settings window is open. Sideways the clock stays on the left.\n\nDefault: 10 s.",
        "Short pop-ups stay for" to "How long short notes that close by themselves stay on screen: an alarm switched on or off, the explanation pop-ups of the alarm pages, and similar confirmations.\n\n" +
            "Longer gives time to read; shorter gets them out of the way sooner. A pop-up you touch stays open until you close it. Default: 3 s.",
        "Switch colour" to "The colour of every switch in the app, one for the on position and one for the off position, so the state shows at a glance as well as by the knob's place. Choose On, Off, or All (the same colour for the two positions — then only the knob's side shows the state).\n\n" +
            "'+ Create' opens the colour editor (hue, saturation, brightness); the new colour replaces the current one. Switches that can't be used stay dark grey.\n\n" +
            "Default: on = your accent colour, off = a muted grey-blue.",
        "Shadow on player buttons" to "A faint grey shadow under the players' buttons and slider knobs, so they stay visible over bright album pictures.",
        "Menu opacity" to "How see-through menus and search results are — set apart from pop-ups, since menus often sit over lists.",
        "Menu row spacing" to "How close the rows of menus and search results are: tighter shows more rows, looser is easier to tap.",
        "Menu text" to "Text size in menus and search results.",
        "Colour for" to "The app's accent colour: one for files, one for the radio (so you see at a glance what's playing).\n\n" +
            "Tap a circle to use it, long-press to edit it, '+' adds one (up to 5).",
        "Order of the Settings links" to "The order of the links on the main Settings page, for example your most used groups first. Drag a link by its ≡ (or long-press it) to move it; 'Reset order' puts back the original order.",
        "Reset order" to "Puts the links of the main Settings page back in their original order.",
        // ---------------------------------------------------------------- Export
        "Include saved passwords" to "The export file also holds the passwords of your servers, encrypted with the passphrase you type. Without a passphrase, passwords are left out.\n\n" +
            "Keep the file private anyway, and remember the passphrase: it can't be recovered.",
        "Everything" to "Saves the whole setup: settings, NAS, VPN, Internet sources, passwords (with a passphrase), radio favourites and saved radio songs, playlists, alarms and clock, visualisation, subtitles, resume positions, per-video choices, ReplayGain values kept in the app and file associations. Everything the app keeps is in one of these parts.\n\n" +
            "While ticked, the other boxes are greyed — they're all included. Untick it to choose part by part.",
        // ---------------------------------------------------------------- Settings groups (main page links)
        "Audio" to "Start volume, preamp, ReplayGain, loudness and treble, the sound engine and per-output settings (speaker, headphones, each Bluetooth device).",
        "Playback & gestures" to "Gestures in the video player, resuming videos, background playback and its battery setting.",
        "Video" to "Picture (decoding, deinterlacing), subtitle format, subtitle languages and downloads, on-screen button size, video lists autoscroll and rotation. Hue and saturation are in the video player (Hue/Sat).",
        "Radio" to "Keeping the radio ready to save songs, the buffer, where and how saved songs are stored, free-space limits.",
        "Look" to "Colours, button size, menus and pop-ups, list row spacing, the order of the player rows and of these links.",
        "Library & files" to "Indexing of network folders, scanning, watching folders, album pictures, removed files.",
        "Network & NAS" to "SMB1, Spotify links and how home servers are reached.",
        "VPN" to "Reaching your NAS away from home: VPN status, asking to start it, the VPN app and your servers' VPN addresses.",
        "Alarm clock" to "Alarms, what wakes you and how, snooze, wake check, the clock's look and the screen saver.",
        "Visualisation" to "Patterns and colours shown with the music, and the frequency range they follow.",
        "Export / import" to "Save your setup to one file, or bring one in — whole or part by part.",
        "Logs" to "What the app did and why something failed, page by page; the sound check, collecting error logs to send, and caches.",
        // ---------------------------------------------------------------- Clock aspect
        "24-hour" to "The digital clock shows 0–23 h. Off: 12-hour with AM / PM.",
        "Seconds" to "Shows the seconds hand (analogue) or seconds digits (digital). Off: a calmer clock that changes once a minute — slightly less battery on the screen saver.",
        "Charging dimming" to "How dark the clock screen saver gets while the phone charges, for a bedside phone at night. Only the screen saver dims, never the clock in the player.",
        "Thickness" to "Thickness of the hands and lines of this clock design. Each design keeps its own value.",
        "Circle size" to "Size of the clock's round face inside its square.",
        "Date size" to "Size of the date shown on the clock.",
        "Ring" to "The circle around the clock face: shown or hidden.",
        "Ticks" to "The hour (and minute) marks around the face.",
        "Hands" to "Style of the clock's hands.",
        "Digits size" to "Size of the hour digits around the face or of the digital time.",
        "Bold digits" to "Heavier digits, easier to read from across a room.",
        "Date under the time" to "The date sits under the time instead of inside the face; never covered by the hands.",
        "Dimming" to "How dark the clock gets.",
        "Start date" to "The first day this alarm can ring; the repeat (days or dates) begins from there.",
        "Button" to "A snooze button on the ringing screen: tap the value to choose its snooze time.",
        // ---------------------------------------------------------------- Visualisation
        "Movement" to "How fast the pattern moves and changes shape with the music. Low is calm, for a bedside screen; high is lively.",
        "Colour mixing" to "How much neighbouring colours blend into each other: low keeps clear patches, high gives soft gradients.",
        "Colour speed" to "How fast the colours drift through your chosen colours.",
        "Definition" to "How fine the pattern is drawn: more definition is sharper but uses more battery on long sessions.",
        "Brightness" to "Overall brightness of the visualisation (the full-screen visualisation's own brightness is changed by sliding up / down on the left half of the screen; the right half sets the sound volume).",
        "Frequencies followed" to "The part of the sound the pattern follows, from bass to treble. Narrow it to the bass to move with the beat; widen it for everything.",
        // ---------------------------------------------------------------- Permissions (setup wizard)
        "Notifications" to "Lets the app post notifications: the player's controls in the notification shade and on the lock screen, the alarm notice with Snooze / Stop, and the next alarm.\n\n" +
            "Without it, playback still works inside the app, but there are no controls outside it and alarms ring without their notice.",
        "Music and videos" to "Lets the app read the songs and videos stored on the phone and the SD card (through Android's media index).\n\n" +
            "Without it, the Library only shows NAS and Internet sources.",
        "Nearby devices (Bluetooth)" to "Lets the app see the names of Bluetooth outputs and ask which codec (SBC, AAC, LDAC…) and rate they use; needed for the Bluetooth quality mode.\n\n" +
            "Without it, Bluetooth still plays, but outputs show without names and codec details.",
        "Alarms and reminders" to "Lets alarms ring exactly on the minute, even with the phone asleep (Android's exact alarms).\n\n" +
            "Without it, Android may deliver alarms minutes late, or not at all in deep sleep.",
        "Full-screen notifications" to "Lets the ringing alarm screen open above the lock screen, so you can stop or snooze it without unlocking.\n\n" +
            "Without it, a ringing alarm only shows as a notification.",
        "Battery unrestricted" to "Tells Android not to pause the app in the background, so playlists, radio recordings and alarm preparation keep going with the screen off.\n\n" +
            "The app only works in the background while it plays, records or prepares an alarm.",
        "Calendar" to "Lets the app add your alarms to the phone's calendar (on the calendar you choose), so they show next to your appointments. Nothing else in your calendar is read or changed.",
        "Location (optional)" to "Lets the clock's weather follow where you are. Only a rough location is used, once in a while.\n\nWithout it, the weather is for the city you choose on the Alarm clock page.",
        "Screen saver" to "Android shows a screen saver while the phone charges; choose 'vlc interface clock' in Android's screen saver settings to see the app's clock there.\n\nNothing to grant: it's a choice in Android's settings.",
        // ---------------------------------------------------------------- File associations (2026-11-22 08:24)
        "File associations" to "Which media files and radio links open with this app when you open them from another app (file manager, mail, downloads, messages, a web page's icy:// link).\n\n" +
            "Each type has a switch: switched on, Android offers vlc interface for that type; switched off, it doesn't (the app still plays those files from its own Library). Web links (http / https) aren't set here.\n\n" +
            "\"Main app\" means Android opens that type with this app without asking. Android doesn't let an app make itself the main app: you choose it in Android's own list (\"Always\"), and when another app is the main one you clear it in that app's settings first. \"Make it the main app…\" under a type walks you through it and checks the result.\n\n" +
            "Uninstalling the app removes all of this by itself: Android deletes every type the app was offered for and every \"Always\" choice made for it, and the types go back to the other apps or to Android's question. An update keeps them as set.",
        "Register for a type" to "The app is now offered for this type: when you open such a file or link from another app, vlc interface is in Android's list. To make it open them without asking, use \"Make it the main app…\" under the type.",
        "Deregister a type" to "The app is no longer offered for this type: Android won't list it, and if it was the main app for it, another app (or Android's question) takes over. Its own Library still plays these files.",
        "Register for every type" to "Switches every type on at once: audio and video files, radio playlists and radio links. Android then offers vlc interface for all of them; the main app for each stays as it is.",
        "Deregister every type" to "Switches every type off at once: Android no longer offers vlc interface for any file or radio link from other apps. Nothing else changes; the app's own Library plays as before.",
        "Any other audio" to "Kept for compatibility: vlc interface is offered for every audio type, also rare ones not listed above (and the listed ones too). Switched on (default), a listed type switched off is still offered through it; switch this off too to take the app away from that type completely.",
        "Any other video" to "Kept for compatibility: vlc interface is offered for every video type, also rare ones not listed above (and the listed ones too). Switched on (default), a listed type switched off is still offered through it; switch this off too to take the app away from that type completely.",
        "Stop being the main app" to "Every type that opens with vlc interface without asking goes back to Android's question (or to another app set as main). The types stay registered, so the app is still offered in the list.",
    ) + Associations.TYPES.filter { !it.isAny }.associate { t ->
        t.label to (if (t.isLink) "Links starting with ${t.exts} (internet radio and streams) " else "${t.label} files (${t.exts}) ") +
            "opened from another app. The switch says whether vlc interface is offered for them; the line under it says who opens them now: this app as the main app, Android's question each time, or another app.\n\n" +
            "\"Make it the main app…\" shows the steps Android allows: clear the other app's default if there is one, then open one such " + (if (t.isLink) "link" else "file") +
            " and choose vlc interface → \"Always\". Nothing is played during that test."
    }

    /** The setting's own explanation (null when it has only the general one). */
    fun own(title: String): String? = texts[title]

    fun text(title: String, hint: String): String {
        texts[title]?.let { return it }
        texts.entries.firstOrNull { title.startsWith(it.key) }?.let { return it.value }
        return if (hint.isNotEmpty()) hint + "\n\nChanges apply at once and stay until you change them again; the setting is saved with your setup."
            else "This setting applies at once and is saved with your setup."
    }
}
