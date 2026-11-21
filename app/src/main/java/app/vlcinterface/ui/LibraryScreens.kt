@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.vlcinterface.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import app.vlcinterface.data.Station
import app.vlcinterface.data.Zone
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.Browse
import app.vlcinterface.sources.Entry
import app.vlcinterface.sources.InternalSource
import app.vlcinterface.sources.LocalFolder
import app.vlcinterface.sources.M3u
import app.vlcinterface.sources.MusicLibrary
import app.vlcinterface.sources.Net
import app.vlcinterface.sources.RadioBrowser
import app.vlcinterface.sources.RadioStation
import app.vlcinterface.sources.SmbErrors
import app.vlcinterface.sources.Urls
import app.vlcinterface.sources.VlcBrowse
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun LibraryHeader(title: String, openOutput: () -> Unit, nav: Navigator? = null, actions: @Composable () -> Unit = {}) = Scaled {
    val canBack = nav != null && nav.stack.size > 1
    // Title on its own row (17:59, it was squeezed to one letter per line): the buttons on the first row — fitting the
    // width, shrunk evenly when they don't — and "Library" / "Playlists" under them.
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
        FitRow(Modifier.fillMaxWidth(), minGap = 4.dp) {
            if (canBack) BackButton(nav!!)
            OutputPill(openOutput)
            actions()
            if (nav != null) SymButton({ nav.go(Screen.Settings) }) { SymIcon(Icons.Filled.Settings, "Settings", tint = C.muted) }
        }
        Text(title, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
    }
}

@Composable
fun HomeButton(nav: Navigator, tint: Color = C.text) {
    SymButton({ nav.home(); nav.replace(Screen.Library()) }) { SymIcon(Icons.Filled.Home, "Home", tint = tint) }
}

@Composable
fun BackHeader(title: String, nav: Navigator, sub: String = "", afterHome: @Composable () -> Unit = {}, gear: Boolean = title != "Settings",
               actions: @Composable () -> Unit = {}) = Scaled {
    // The page title on its own row (2026-11-21 17:57): symbols on the first row (back, home … on the left; the page's
    // own symbols and the gear / red ✕ on the right), the title (and its line under it) on the second, full width.
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SymButton(onClick = { nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            HomeButton(nav)
            afterHome()
            Spacer(Modifier.weight(1f))
            actions()
            // Settings pages: a red ✕ straight back to the player (main page when no player is open) instead of the gear.
            if (LocalSettingsClose.current) CloseToPlayerButton(nav)
            // Settings symbol on every other page: opens the main Settings page.
            else if (gear) SymButton({ nav.go(Screen.Settings) }) { SymIcon(Icons.Filled.Settings, "Settings", tint = C.muted) }
        }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, bottom = 2.dp)) {
            Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** True on the Settings pages (main and sub-pages): their header shows the red ✕ instead of the gear. */
val LocalSettingsClose = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Red ✕ in a settings page's top-right corner: back to the player that was open, else the main page. */
@Composable
fun CloseToPlayerButton(nav: Navigator) {
    Box(Modifier.padding(horizontal = 6.dp).size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE24B4A))
        .clickable { SettingsNav.toPlayer(nav) }, contentAlignment = Alignment.Center) {
        Text("✕", color = Color.White, fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}

object SettingsNav {
    fun isSettingsPage(s: Screen) = s is Screen.Settings || s is Screen.SoundSettings || s is Screen.SettingsGroup ||
        s is Screen.MediaFolders || s is Screen.Backup || s is Screen.AlarmSettings || s is Screen.ClockAspect

    /** Back to the most recent player in the stack (songs / radio or video); none → the app's main page. */
    fun toPlayer(nav: Navigator) {
        val i = nav.stack.indexOfLast { it is Screen.Music || it is Screen.Player }
        if (i < 0) { nav.home(); nav.replace(Screen.Library()) }
        else while (nav.stack.lastIndex > i) nav.stack.removeAt(nav.stack.lastIndex)
    }

    /** A sub-page's parent: the settings page it was opened from, else the main Settings page. */
    fun toParent(nav: Navigator) {
        val prev = nav.stack.getOrNull(nav.stack.lastIndex - 1)
        if (prev != null && isSettingsPage(prev)) nav.back() else nav.replace(Screen.Settings)
    }
}

/** Save, in a page's top bar: always filled with the accent colour. */
@Composable
fun SaveTopButton(enabled: Boolean = true, label: String = "Save", onClick: () -> Unit) {
    val a = accent()
    Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) a else a.copy(alpha = 0.35f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color(0xFF1A0E05), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LibraryScreen(nav: Navigator, s: Screen.Library, openOutput: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Library", openOutput, nav) {
            when {
                s.tab != 0 || s.sub != 0 -> HomeButton(nav)
                else -> {}
            }
            when {
                s.tab == 1 -> SymButton({ nav.go(Screen.EditSource(Zone.LAN)) }) { SymIcon(Icons.Filled.Add, "Add LAN source", tint = C.text) }
                s.tab == 2 && s.sub == 0 -> SymButton({ nav.go(Screen.EditSource(Zone.INTERNET)) }) { SymIcon(Icons.Filled.Add, "Add internet storage", tint = C.text) }
                else -> {}
            }
            // Open the player that goes with what this tab shows: Radio → radio, Songs / Audio → audio, Video and
            // Streams & IPTV → video.
            val libKind = App.instance.store.state.collectAsState().value.settings.libraryKind
            val (pLabel, pScreen) = when {
                s.tab == 4 -> "Radio" to Screen.Music
                s.tab == 3 -> "Audio" to Screen.Music
                s.tab == 2 && s.sub == 2 -> "Video" to Screen.Player
                libKind == "video" -> "Video" to Screen.Player
                else -> "Audio" to Screen.Music
            }
            val pKind = pLabel.lowercase()
            val pctx = androidx.compose.ui.platform.LocalContext.current
            var swapAsk by remember { mutableStateOf("") }
            // Opens that player: with its own kind loaded now, straight away; otherwise its last queue comes back
            // (paused at its spot) — asked first when something of another kind is playing.
            fun openPlayer(confirmed: Boolean = false) {
                val qk = app.vlcinterface.player.QueueKeeper
                val p = App.instance.player
                val cur = p.state.value.item
                when {
                    qk.kindOf(cur) == pKind -> nav.go(pScreen)
                    !qk.has(pKind) -> {
                        val t = android.widget.Toast.makeText(pctx, "Nothing played in the $pLabel player yet", android.widget.Toast.LENGTH_LONG)
                        t.show(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ t.cancel() }, 3000)
                    }
                    cur != null && p.state.value.isPlaying && !confirmed -> swapAsk = qk.kindOf(cur)
                    else -> {
                        qk.restore(p, pKind, force = true)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ nav.go(pScreen) }, 300)
                    }
                }
            }
            if (swapAsk.isNotEmpty()) AlertDialog(onDismissRequest = { swapAsk = "" }, containerColor = C.panel,
                title = { Text("${swapAsk.replaceFirstChar { it.uppercase() }} is playing", color = C.text) },
                text = { Text("Stop it and bring back your last ${pKind} queue (paused where it was)?", color = C.text, fontSize = 14.sp) },
                confirmButton = { STextButton({ swapAsk = ""; openPlayer(true) }) { Text("Load $pLabel", color = accent(), fontWeight = FontWeight.Bold) } },
                dismissButton = { STextButton({ swapAsk = "" }) { Text("Cancel") } })
            Row(Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, accent().copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .clickable { openPlayer() }.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SymIcon(Icons.Filled.PlayArrow, "Open the $pLabel player", tint = accent())
                Text(pLabel, color = accent(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(4.dp))
            ExitButton()
            // Library menu.
            var libMenu by remember { mutableStateOf(false) }
            var libRg by remember { mutableStateOf(false) }
            Box {
                SymButton({ libMenu = true }) { SymIcon(Icons.Filled.MoreVert, "Library menu", tint = C.text) }
                SDropdownMenu(libMenu, { libMenu = false }) {
                    SMenuItem({ Text("ReplayGain scan — entire library…") }, { libMenu = false; libRg = true })
                    SMenuItem({ Text("ReplayGain scan page") }, { libMenu = false; nav.go(Screen.RgScan) })
                }
            }
            if (libRg) LibraryRgDialog(nav) { libRg = false }
        }
        // Audio / Video switch: every Library tab then lists only that kind of file.
        val kind = App.instance.store.state.collectAsState().value.settings.libraryKind
        if (!(s.tab == 2 && s.sub != 0) && s.tab != 3 && s.tab != 4) Scaled { Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(3.dp)) {
            listOf("audio" to "♫  Audio", "video" to "▶  Video").forEach { (k, t) ->
                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(if (kind == k) accent() else Color.Transparent)
                    .clickable { App.instance.store.update { it.copy(settings = it.settings.copy(libraryKind = k)) } }, contentAlignment = Alignment.Center) {
                    Text(t, color = if (kind == k) Color(0xFF1A0E05) else C.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        } // Audio / Video switch
        // Internal · LAN · Internet · Radio · Songs (Radio is its own section, tab 4; shrinks to fit narrow screens).
        AutoFitRow(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), spacing = 8.dp) {
            listOf(0 to "Internal", 1 to "LAN", 2 to "Internet", 4 to "Radio", 3 to "Songs").forEach { (i, t) ->
                Pill(t, filled = s.tab == i) { if (s.tab != i) nav.go(Screen.Library(i, if (i == 2 && s.sub == 1) 0 else s.sub)) }
            }
        }
        if (s.tab == 2) Scaled {
            Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(3.dp)) {
                // Radio moved to its own section: Internet keeps Storage and Streams & IPTV (sub 0 and 2).
                listOf(0 to "Storage", 2 to "Streams & IPTV").forEach { (i, t) ->
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(if (s.sub == i) Color(0xFF2A2F38) else Color.Transparent)
                        .clickable { if (s.sub != i) nav.go(Screen.Library(2, i)) }, contentAlignment = Alignment.Center) {
                        Text(t, color = if (s.sub == i) C.text else C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (s.tab) {
                0 -> InternalTab(nav)
                1 -> SourcesTab(nav, Zone.LAN)
                3 -> SongsTab(nav)
                4 -> RadioTab(nav)
                else -> when (s.sub) {
                    0, 1 -> SourcesTab(nav, Zone.INTERNET)
                    else -> StreamsTab(nav)
                }
            }
        }
        BottomBar(nav, current = 0)
    }
}

/** Library · Playlists · Settings, with the playing bar above it. */
@Composable
fun BottomBar(nav: Navigator, current: Int) {
    val p by App.instance.player.state.collectAsState()
    Column {
        if (p.item != null) MiniPlayer(nav)
        Scaled {
        Row(Modifier.fillMaxWidth().height(60.dp).background(C.bg), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf("Library", "Playlists", "Settings").forEachIndexed { i, label ->
                Text(label, color = if (current == i) accent() else C.muted, fontWeight = if (current == i) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        when (i) {
                            0 -> { nav.home(); nav.replace(Screen.Library()) }
                            1 -> { nav.home(); nav.go(Screen.Playlists) }
                            else -> nav.go(Screen.Settings)
                        }
                    }.padding(12.dp))
            }
        }
        }
    }
}

/** Playing bar: tap opens the folder of the playing file; the arrow opens the full player. */
@Composable
fun MiniPlayer(nav: Navigator) {
    val p by App.instance.player.state.collectAsState()
    val item = p.item ?: return
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card2)) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { openOrigin(nav) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title + if (item.isRadio) " · Live" else "", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                p.error.isNotEmpty() -> p.error
                p.nowPlaying.isNotEmpty() -> "Now: ${p.nowPlaying}"
                p.buffering < 100f && !p.isPlaying -> "Buffering…"
                item.isRadio -> "Radio"
                else -> listOfNotNull(p.metaArtist.ifEmpty { null }, "${fmtTime(p.time)} / ${fmtTime(p.length)}").joinToString(" · ")
            }
            Text(sub, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SymButton({ nav.go(if (item.isMusic || item.isRadio) Screen.Music else Screen.Player) }) {
            SymIcon(Icons.Filled.KeyboardArrowUp, "Open player", tint = C.text)
        }
        SymButton({ App.instance.player.togglePause() }) {
            Text(if (p.isPlaying) "❚❚" else "▶", color = accent(), fontSize = 18.sp)
        }
        SymButton({ App.instance.player.stop() }) { Text("✕", color = C.muted, fontSize = 16.sp) }
    }
    // Progress along the bottom edge of the bar (not for live radio/streams).
    val len = if (item.endMs > 0) item.endMs - item.startMs else p.length
    if (len > 0) {
        val pos = (p.time - (if (item.startMs > 0) item.startMs else 0)).coerceAtLeast(0)
        Box(Modifier.fillMaxWidth().height(3.dp).background(C.line)) {
            Box(Modifier.fillMaxWidth((pos.toFloat() / len).coerceIn(0f, 1f)).fillMaxHeight().background(accent()))
        }
    }
    }
}

// ---------------- Internal ----------------

@Composable
fun InternalTab(nav: Navigator) {
    val ctx = LocalContext.current
    val st by App.instance.store.state.collectAsState()
    var folders by remember { mutableStateOf(InternalCache.folders) }      // last list at once, then refreshed
    var busy by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<LocalFolder?>(null) }
    var addFolder by remember { mutableStateOf<LocalFolder?>(null) }
    var rgFolder by remember { mutableStateOf<LocalFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<Pair<LocalFolder, List<Entry>>?>(null) }
    var blackFolder by remember { mutableStateOf<LocalFolder?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { folders = InternalSource.folders(ctx).also { InternalCache.folders = it } }
    // Same place in the list after going into a folder and back.
    val scroll = rememberKeptScroll("lib:internal:" + App.instance.store.current.settings.libraryKind, folders != null)
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp)) {
        // "Continue watching" card removed (user request); videos still resume where they were left.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("On this phone", Modifier.weight(1f))
            Pill(if (busy == "*") "Rescanning…" else "Rescan all") {
                scope.launch {
                    busy = "*"
                    folders?.forEach { f -> withTimeoutOrNull(20_000) { InternalSource.rescan(ctx, f.relativePath) } }
                    val now = System.currentTimeMillis()
                    App.instance.store.update { s -> s.copy(scanned = s.scanned + (folders ?: emptyList()).associate { it.id to now }) }
                    reload++; busy = ""
                }
            }
        }
        when {
            folders == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
            folders!!.isEmpty() -> Text("No videos or music found. Allow access to videos and music when Android asks, then tap Rescan all.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            else -> folders!!.filter { if (st.settings.libraryKind == "video") it.videos > 0 else it.songs > 0 }.also { shown ->
                if (shown.isEmpty()) Text(if (st.settings.libraryKind == "video") "No videos on the phone." else "No music on the phone.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            }.forEach { f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(onClick = { nav.go(Screen.Folder(f)) }, onLongClick = { menuFor = f }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.card2), contentAlignment = Alignment.Center) { Text("▤", color = accent()) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(f.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val n = if (st.settings.libraryKind == "video") f.videos else f.songs
                        Text("$n ${if (st.settings.libraryKind == "video") (if (n == 1) "video" else "videos") else (if (n == 1) "song" else "songs")} · ${ago(st.scanned[f.id] ?: 0)}", color = C.muted, fontSize = 13.sp)
                    }
                    Box {
                        if (menuFor == f) EntryMenu(Entry(f.name, f.relativePath, true), true, null, onDismiss = { menuFor = null },
                            onPlayNext = { scope.launch { App.instance.player.playNext(ofKind(MusicLibrary.collectLocal(ctx, f, true))) } },
                            onQueue = { scope.launch { App.instance.player.addToQueue(ofKind(MusicLibrary.collectLocal(ctx, f, true))) } },
                            onAddPlaylist = { addFolder = f },
                            onRgScan = if (st.settings.libraryKind == "audio") ({ rgFolder = f }) else null,
                            onDelete = null,   // deleting files: only from Settings → Delete a file
                            onRemove = { blackFolder = f },
                            onPlay = { scope.launch { ofKind(MusicLibrary.collectLocal(ctx, f, true)).takeIf { it.isNotEmpty() }?.let { l ->
                                App.instance.player.playQueue(l, 0, app.vlcinterface.player.Origin("local", title = f.name, localFolderId = f.id))
                                nav.go(if (l.first().isMusic) Screen.Music else Screen.Player) } } },
                            folderInfo = FolderInfo(f.name, f.relativePath, "Phone"), collect = { ofKind(MusicLibrary.collectLocal(ctx, f, true)) })
                    }
                    SymButton({
                        scope.launch {
                            busy = f.id
                            withTimeoutOrNull(20_000) { InternalSource.rescan(ctx, f.relativePath) }
                            App.instance.store.update { s -> s.copy(scanned = s.scanned + (f.id to System.currentTimeMillis())) }
                            reload++; busy = ""
                        }
                    }) {
                        if (busy == f.id) CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp)
                        else SymIcon(Icons.Filled.Refresh, "Rescan ${f.name}", tint = C.muted)
                    }
                }
                Divider()
            }
        }
        Box(Modifier.height(16.dp))
    }
    addFolder?.let { f -> AddToPlaylistDialog({ ofKind(MusicLibrary.collectLocal(ctx, f, true)) }) { addFolder = null } }
    rgFolder?.let { f -> RgScanDialog(nav, { MusicLibrary.collectLocal(ctx, f, true) }) { rgFolder = null } }
    blackFolder?.let { f ->
        FolderContentConfirm("Remove folder from lists?", f.relativePath + "\nNever re-added (rescans, adds). Nothing is deleted.", false, "Remove from lists",
            { phoneFolderFiles(ctx, f).map { it.second } },
            onConfirm = { HiddenFiles.hide("local:${f.id}"); blackFolder = null; reload++ }, onCancel = { blackFolder = null })
    }
    deleteFolder?.let { (f, files) ->
        if (files.isEmpty()) deleteFolder = null
        else DeleteDialog(files.map { it.uri }, f.name, files.sumOf { it.size.coerceAtLeast(0) }, files.size) { deleteFolder = null; reload++ }
    }
}

@Composable
fun FolderScreen(nav: Navigator, folder: LocalFolder, highlight: String) {
    val ctx = LocalContext.current
    // Shown at once from the last listing (so coming back keeps your place), then refreshed.
    var files by remember { mutableStateOf(ListCache.get("local:" + folder.id)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(folder.id, reload) { files = InternalSource.files(ctx, folder).also { ListCache.put("local:" + folder.id, it) } }
    Column(Modifier.fillMaxSize()) {
        BackHeader(folder.name, nav, folder.relativePath)
        Box(Modifier.weight(1f)) {
            MediaList(nav, files, "", folder.name.removeSuffix(" (SD card)"), null, "local:${folder.volume}:${folder.relativePath}",
                Origin("local", folder.volume, folder.relativePath, folder.name, localFolderId = folder.id), highlight,
                onOpenDir = {}, onRefresh = { reload++ })
        }
        BottomBar(nav, current = 0)
    }
}

// ---------------- LAN + Internet storage ----------------

@Composable
fun SourcesTab(nav: Navigator, zone: Zone) {
    val st by App.instance.store.state.collectAsState()
    val list = st.sources.filter { it.zone == zone && it.proto != "m3u" }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    var found by remember { mutableStateOf<List<Entry>?>(null) }
    // Re-check reachability every 20 s while the tab is visible.
    LaunchedEffect(list.map { it.id }) {
        while (true) {
            list.forEach { s ->
                launch {
                    val host = Urls.hostOnly(s.host)
                    online[s.id] = if (s.proto == "upnp") true else Net.reachable(host, if (s.port > 0) s.port else s.defaultPort)
                }
            }
            kotlinx.coroutines.delay(20_000)
        }
    }
    if (zone == Zone.LAN) LaunchedEffect(Unit) { found = VlcBrowse.discover() }
    val scroll = rememberKeptScroll("lib:sources:$zone")
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (zone == Zone.LAN) CardBox(Modifier.clickable { nav.go(Screen.ServerCheck("")) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check a server", color = C.text, fontWeight = FontWeight.SemiBold)
                    Text("Type a host name or IP — see what it offers and what you still need to connect", color = C.muted, fontSize = 13.sp)
                }
                Text("›", color = C.muted, fontSize = 20.sp)
            }
        }
        SectionTitle(if (zone == Zone.LAN) "Saved on your LAN" else "Remote storage")
        if (list.isEmpty()) Text(
            if (zone == Zone.LAN) "No LAN sources yet. Tap + to add your NAS, or save one found below."
            else "No remote storage yet. Tap + or a preset below to add a seedbox, Nextcloud or FTP server.",
            color = C.muted, fontSize = 14.sp
        )
        list.forEach { s -> SourceCard(nav, s, online[s.id]) }
        if (zone == Zone.LAN) {
            SectionTitle("Found on your LAN")
            CardBox {
                when {
                    found == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp)
                        Text("  Looking for servers…", color = C.muted, fontSize = 14.sp)
                    }
                    found!!.isEmpty() -> Text("Nothing announced itself. You can still add a server by its address.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    else -> found!!.forEach { e ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = C.text, fontSize = 14.sp)
                                Text(e.meta + " · " + e.uri, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val proto = e.uri.substringBefore("://").lowercase()
                            val draft = remember(e.uri) { if (proto == "upnp") Source(zone = Zone.LAN, proto = "upnp", name = e.name, host = e.uri)
                                else Source(zone = Zone.LAN, proto = proto, name = e.name, host = Urls.hostOnly(e.uri.substringAfter("://"))) }
                            // A location found on the LAN (16:59): "+ Folder" too — adding a folder saves the location with it.
                            var pickFound by remember { mutableStateOf(false) }
                            if (proto != "upnp") Pill("+ Folder") { pickFound = true }
                            if (pickFound) AddFolderDialog(draft) { pickFound = false }
                            Box(Modifier.width(6.dp))
                            Pill("Save as preset") { nav.go(Screen.EditSource(Zone.LAN, draft = draft)) }
                        }
                        Divider()
                    }
                }
            }
        } else {
            SectionTitle("Add remote storage")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sftp" to "SFTP", "webdav" to "WebDAV", "ftps" to "FTP/FTPS", "http" to "HTTP").forEach { (p, l) ->
                    Pill(l) { nav.go(Screen.EditSource(Zone.INTERNET, draft = Source(zone = Zone.INTERNET, proto = p))) }
                }
            }
        }
        Box(Modifier.height(16.dp))
    }
}

@Composable
fun SourceCard(nav: Navigator, s: Source, online: Boolean?) {
    val a = accent()
    CardBox {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp).clickable {
            nav.go(Screen.Browse(s.id, Urls.forSource(s), s.name))
        }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                Text(if (s.smbVersion == "SMB1") "SMB1" else s.proto.uppercase().take(6), color = a, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.name.ifEmpty { s.host }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(Urls.forSource(s), color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val (label, col) = when (online) { null -> "…" to C.muted; true -> "Online" to C.ok; false -> "Offline" to C.muted }
            Text(label, color = col, fontSize = 12.sp)
            if (s.zone == Zone.INTERNET) Text(if (s.wifiOnly) " Wi-Fi only" else " Any network", color = C.muted, fontSize = 11.sp)
            SymButton({ nav.go(Screen.LogPage(ServerLog.keyOf(s.host))) }) { SymIcon(Icons.Filled.Info, "Log for ${s.name}", tint = C.muted) }
            SymButton({ nav.go(Screen.EditSource(s.zone, s.id)) }) { SymIcon(Icons.Filled.MoreVert, "Edit ${s.name}", tint = C.muted) }
        }
        // LAN servers: which address to use — Auto, the LAN one or any stored one (VPN …); + stores another,
        // long-press an address to remove it.
        if (s.zone == Zone.LAN && s.proto != "upnp") {
            val vl = app.vlcinterface.sources.VpnLink
            val vpnOn by vl.vpnUp.collectAsState()
            val inUse = remember(s, vpnOn, online) { vl.hostNow(s) }
            var adding by remember { mutableStateOf(false) }
            var removing by remember { mutableStateOf<String?>(null) }
            FlowRow(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Address", color = C.muted, fontSize = 12.sp)
                if (s.vpnHost.isNotBlank()) Pill("Auto", selected = s.addrMode == "auto" || s.addrMode.isBlank()) { vl.setMode(s, "auto") }
                Pill("LAN ${s.host}", selected = s.addrMode == "lan" || (s.vpnHost.isBlank() && (s.addrMode == "auto" || s.addrMode.isBlank()))) { vl.setMode(s, "lan") }
                s.altHosts.forEach { h ->
                    Box(Modifier.combinedClickable(onClick = { vl.setMode(s, h) }, onLongClick = { removing = h })) {
                        Pill((if (h == s.vpnHost) "VPN " else "") + h, selected = s.addrMode == h || (s.addrMode == "vpn" && h == s.vpnHost)) { vl.setMode(s, h) }
                    }
                }
                Pill("+") { adding = true }
            }
            if (s.vpnHost.isNotBlank() || s.altHosts.isNotEmpty())
                Text("In use: $inUse" + if (inUse != s.host) " (instead of ${s.host})" else "", color = a, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
            if (adding) {
                var ip by remember { mutableStateOf("") }
                AlertDialog(onDismissRequest = { adding = false }, containerColor = C.panel,
                    title = { Text("Another address for ${s.name.ifBlank { s.host }}", color = C.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Same server on another network (ZeroTier, Tailscale …). Its folders and files stay the same.", color = C.muted, fontSize = 12.sp)
                            OutlinedTextField(ip, { ip = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Address") },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                        }
                    },
                    confirmButton = { STextButton({ vl.addAddress(s, ip); adding = false }, enabled = ip.isNotBlank()) { Text("Save", color = a) } },
                    dismissButton = { STextButton({ adding = false }) { Text("Cancel") } })
            }
            removing?.let { h ->
                AlertDialog(onDismissRequest = { removing = null }, containerColor = C.panel,
                    title = { Text("Remove $h?", color = C.text) },
                    text = { Text("${s.name.ifBlank { s.host }} keeps its other addresses.", color = C.muted, fontSize = 13.sp) },
                    confirmButton = { STextButton({ vl.removeAddress(s, h); removing = null }) { Text("Remove", color = C.bad) } },
                    dismissButton = { STextButton({ removing = null }) { Text("Cancel") } })
            }
        }
        FlowRow(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.pins.forEach { pin ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF252A32)).clickable {
                    // A pin holds the folder's full path on the server (share included): built from the server
                    // alone, so the share isn't put in twice (".../Transcend/Transcend/...").
                    nav.go(Screen.Browse(s.id, Urls.forSource(s.copy(path = ""), pin), pin.trim('/').substringAfterLast('/')))
                }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text("▤ " + pin.trim('/').substringAfterLast('/'), color = C.text, fontSize = 13.sp) }
                // Right beside the bookmark: index it now for search (orange = indexed; spinner while indexing).
                val idxBusy by app.vlcinterface.sources.NetIndex.busy.collectAsState()
                val idxVer by app.vlcinterface.sources.NetIndex.version.collectAsState()
                val indexed = remember(idxVer) { app.vlcinterface.sources.NetIndex.has(s, pin) }
                val ctxIdx = androidx.compose.ui.platform.LocalContext.current
                Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (indexed) a else C.line, RoundedCornerShape(8.dp)).clickable {
                    app.vlcinterface.sources.NetIndex.build(s, pin)
                    android.widget.Toast.makeText(ctxIdx, "Indexing ${pin.trim('/').substringAfterLast('/')} for search…", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    if (idxBusy.contains(pin.trim('/').substringAfterLast('/'))) CircularProgressIndicator(color = a, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(if (indexed) "🔍 Indexed" else "🔍 Index", color = if (indexed) a else C.muted, fontSize = 13.sp)
                }
            }
            // Add a folder of this location (2026-11-21 16:57): pick it in a small folder browser; it's pinned and indexed.
            var pickFolder by remember { mutableStateOf(false) }
            if (s.proto != "upnp") Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, a, RoundedCornerShape(8.dp)).clickable { pickFolder = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)) { Text("+ Folder", color = a, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            if (pickFolder) AddFolderDialog(s) { pickFolder = false }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.line, RoundedCornerShape(8.dp)).clickable {
                App.instance.store.update { st -> st.copy(sources = st.sources.map { if (it.id == s.id) it.copy(lastScan = System.currentTimeMillis()) else it }) }
                app.vlcinterface.sources.NetIndex.rebuild(s)          // its pinned folders' index too
                nav.go(Screen.Browse(s.id, Urls.forSource(s), s.name, highlight = "\u0000rescan"))
            }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text("↻ Rescan", color = a, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (s.proto == "smb") WriteAccessRow(s)
    }
}

@Composable
fun BrowseScreen(nav: Navigator, b: Screen.Browse) {
    val st by App.instance.store.state.collectAsState()
    val src = st.sources.firstOrNull { it.id == b.sourceId }
    var entries by remember(b.uri) { mutableStateOf(ListCache.get(b.uri)) }
    var error by remember(b.uri) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(if (b.highlight == "\u0000rescan") 1 else 0) }
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(b.uri, reload) {
        if (reload > 0 || entries == null) entries = null
        error = ""
        if (src == null) { error = "Source not found"; return@LaunchedEffect }
        val started = System.currentTimeMillis()
        val r = Browse.list(src, b.uri, if (reload > 0) "rescan" else "browse")
        r.onSuccess { l ->
            entries = MusicLibrary.withCachedTags(l).also { ListCache.put(b.uri, it) }
            // Videos of this folder that are gone: what was kept for them (settings, position, delays…) is forgotten.
            app.vlcinterface.player.VideoFileCleanup.folderListed(b.uri, l.map { it.uri })
            // Tags are read only on a manual Rescan (no automatic scanning).
            if (reload > 0 && l.any { it.isAudio }) {
                scanning = true
                entries = MusicLibrary.scanTags(src, l, force = true)
                scanning = false
                App.instance.store.update { s -> s.copy(sources = s.sources.map { if (it.id == src.id) it.copy(lastScan = System.currentTimeMillis()) else it }) }
            }
        }.onFailure { e ->
            val why = SmbErrors.explain(e, src.user.isEmpty(), b.uri.substringAfter("://").split('/').getOrElse(1) { "" })
            error = "Could not open: $why"
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(b.title, nav, if (scanning) "Reading tags…" else b.uri) {
            if (src != null) {
                val ctxPin = androidx.compose.ui.platform.LocalContext.current
                val path = android.net.Uri.decode(b.uri.substringAfter("://").substringAfter('/', "")).let { "/" + it.trimEnd('/') }
                val pinned = src.pins.contains(path)
                SymButton({
                    App.instance.store.update { s -> s.copy(sources = s.sources.map { if (it.id == src.id) it.copy(pins = if (pinned) it.pins - path else it.pins + path) else it }) }
                    // Pinned folders are indexed (songs searchable from the Songs list / player); unpinned → index dropped.
                    if (pinned) app.vlcinterface.sources.NetIndex.remove(src, path) else {
                        app.vlcinterface.sources.NetIndex.build(src, path)
                        android.widget.Toast.makeText(ctxPin, "Pinned — indexing its songs for search in the background", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { SymIcon(Icons.Filled.Star, if (pinned) "Unpin folder" else "Pin folder", tint = if (pinned) accent() else C.muted) }
                // Index this folder now (songs + tags, subfolders) for search; pins it too if it isn't yet.
                val indexing by app.vlcinterface.sources.NetIndex.busy.collectAsState()
                SymButton({
                    if (!pinned) App.instance.store.update { s -> s.copy(sources = s.sources.map { if (it.id == src.id) it.copy(pins = it.pins + path) else it }) }
                    app.vlcinterface.sources.NetIndex.build(src, path)
                    android.widget.Toast.makeText(ctxPin, "Indexing this folder for search…", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    if (indexing.isNotEmpty()) CircularProgressIndicator(color = accent(), strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else SymIcon(Icons.Filled.Search, "Index this folder for search", tint = if (app.vlcinterface.sources.NetIndex.has(src, path)) accent() else C.muted)
                }
            }
            SymButton({ reload++ }) {
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp)
                else SymIcon(Icons.Filled.Refresh, "Rescan", tint = C.text)
            }
        }
        Box(Modifier.weight(1f)) {
            MediaList(nav, entries, error, Urls.nameOf(b.uri), src, b.uri, Origin("folder", b.sourceId, b.uri, b.title),
                if (b.highlight.startsWith("\u0000")) "" else b.highlight,
                onOpenDir = { e -> nav.go(Screen.Browse(b.sourceId, e.uri, e.name)) }, onRefresh = { reload++ })
        }
        BottomBar(nav, current = 0)
    }
}

// ---------------- Radio ----------------

@Composable
fun RadioTab(nav: Navigator) {
    val st by App.instance.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RadioStation>?>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }
    fun run(name: String = "", tag: String = "", country: String = "") {
        scope.launch {
            results = null; error = ""
            RadioBrowser.search(name, tag, country).onSuccess { results = it }.onFailure { results = emptyList(); error = it.message ?: "Search failed" }
        }
    }
    // Stations play in the audio player (radio mode); the favourites are its station list.
    fun play(name: String, url: String) {
        App.instance.player.playStation(name, url)
        nav.go(Screen.Music)
    }
    val scroll = rememberKeptScroll("lib:radio")
    // Favourites export / import: a standard M3U playlist (name, link, logo, details), readable by other players too.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val list = App.instance.store.current.stations
        val text = buildString {
            append("#EXTM3U\n")
            list.forEach { s ->
                append("#EXTINF:-1")
                if (s.favicon.isNotBlank()) append(" tvg-logo=\"${s.favicon.replace("\"", "")}\"")
                if (s.meta.isNotBlank()) append(" group-title=\"${s.meta.replace("\"", "")}\"")
                append(",${s.name}\n${s.url}\n")
            }
        }
        val ok = try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
        android.widget.Toast.makeText(ctx, if (ok) "${list.size} stations exported" else "Could not write the file", android.widget.Toast.LENGTH_LONG).show()
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } catch (_: Exception) { null } ?: ""
        // M3U / M3U8 (#EXTINF names, logos, details) or a plain list of links; stations already there are skipped.
        val found = ArrayList<app.vlcinterface.data.Station>()
        var name = ""; var logo = ""; var meta = ""
        text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(',').trim()
                    logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
                    meta = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
                }
                line.isEmpty() || line.startsWith("#") -> {}
                line.contains("://") -> {
                    found += app.vlcinterface.data.Station(name = name.ifEmpty { line.substringAfter("://").substringBefore('/') }, url = line, meta = meta, favicon = logo)
                    name = ""; logo = ""; meta = ""
                }
            }
        }
        var added = 0
        App.instance.store.update { x ->
            val have = x.stations.map { it.url }.toMutableSet()
            val fresh = found.filter { have.add(it.url) }
            added = fresh.size
            x.copy(stations = x.stations + fresh)
        }
        android.widget.Toast.makeText(ctx, when {
            found.isEmpty() -> "No stations found in that file"
            added == 0 -> "All ${found.size} stations were already in your favourites"
            else -> "$added stations added" + if (added < found.size) " (${found.size - added} already there)" else ""
        }, android.widget.Toast.LENGTH_LONG).show()
    }
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("⬆  Export favourites", modifier = Modifier.weight(1f)) {
                if (App.instance.store.current.stations.isEmpty())
                    android.widget.Toast.makeText(ctx, "No favourite stations to export yet", android.widget.Toast.LENGTH_SHORT).show()
                else exporter.launch("radio-favourites-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".m3u")
            }
            Pill("⬇  Import favourites", modifier = Modifier.weight(1f)) { importer.launch(arrayOf("*/*")) }
        }
        // ▲ ▼ step through the results (highlighted, scrolled into view).
        val reqs = remember(results) { List(results?.size ?: 0) { androidx.compose.foundation.relocation.BringIntoViewRequester() } }
        var at by remember(results) { mutableStateOf(-1) }
        val sscope = androidx.compose.runtime.rememberCoroutineScope()
        fun step(k: Int) { if (reqs.isEmpty()) return; at = (k + reqs.size) % reqs.size; sscope.launch { reqs[at].bringIntoView() } }
        SmallSearchBar(q, { q = it }, "Search stations by name", reqs.size, at.coerceAtLeast(0), { step(at - 1) }, { step(at + 1) }, onSearch = { run(name = q) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Jazz", "News", "Rock", "Classical", "Pop", "Chill").forEach { g -> Pill(g) { run(tag = g) } }
            Pill("Near me") { run(country = java.util.Locale.getDefault().country) }
        }
        if (st.stations.isNotEmpty()) {
            SectionTitle("Favourite stations")
            var renameS by remember { mutableStateOf<Station?>(null) }
            renameS?.let { s -> StationRenameDialog(s) { renameS = null } }
            CardBox {
                st.stations.forEach { s ->
                    // Long-press: rename the station.
                    StationRow(s.name, s.meta, fav = true, onFav = {
                        App.instance.store.update { x -> x.copy(stations = x.stations.filterNot { it.id == s.id }) }
                    }, onLong = { renameS = s }) { play(s.name, s.url) }
                    Divider()
                }
            }
        }
        when {
            results == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
            error.isNotEmpty() -> Text(error, color = C.bad, fontSize = 14.sp)
            results!!.isNotEmpty() -> {
                SectionTitle("Results")
                CardBox {
                    results!!.forEachIndexed { ri, r ->
                        val fav = st.stations.any { it.url == r.url }
                        Box(Modifier.then(if (ri < reqs.size) Modifier.bringIntoViewRequester(reqs[ri]) else Modifier)
                            .background(if (ri == at) accent().copy(alpha = 0.18f) else Color.Transparent)) {
                        StationRow(r.name, r.meta, fav, onFav = {
                            App.instance.store.update { x ->
                                if (fav) x.copy(stations = x.stations.filterNot { it.url == r.url })
                                else x.copy(stations = x.stations + Station(name = r.name, url = r.url, meta = r.meta, favicon = r.favicon))
                            }
                        }) { play(r.name, r.url) }
                        }
                        Divider()
                    }
                }
            }
        }
        SectionTitle("Add a station by URL")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(addUrl, { addUrl = it }, Modifier.weight(1f), placeholder = { Text("https://…/stream or .pls / .m3u") }, singleLine = true)
            SymButton({
                if (addUrl.isNotBlank()) {
                    val u = addUrl.trim()
                    App.instance.store.update { x -> x.copy(stations = x.stations + Station(name = Urls.nameOf(u), url = u, meta = "added by URL")) }
                    addUrl = ""
                }
            }) { SymIcon(Icons.Filled.Add, "Add station", tint = C.text) }
        }
        Box(Modifier.height(16.dp))
    }
}

@Composable
fun StationRow(name: String, meta: String, fav: Boolean, onFav: () -> Unit, onLong: (() -> Unit)? = null, list: String = if (fav) "radioFav" else "radioFind", onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).combinedClickable(onClick = onPlay, onLongClick = onLong).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MarqueeText(name, C.text, Modifier.fillMaxWidth(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, list = list)
            if (meta.isNotEmpty()) Text(stationMetaShort(meta), color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SymButton(onFav) { SymIcon(Icons.Filled.Star, if (fav) "Remove from favourites" else "Add to favourites", tint = if (fav) accent() else C.muted) }
        SymButton(onPlay) { SymIcon(Icons.Filled.PlayArrow, "Play $name", tint = accent()) }
    }
}

// ---------------- Streams & IPTV ----------------

@Composable
fun StreamsTab(nav: Navigator) {
    val st by App.instance.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var openList by remember { mutableStateOf<Source?>(null) }
    var channels by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf("") }
    val lists = st.sources.filter { it.proto == "m3u" }
    val scroll = rememberKeptScroll("lib:streams")
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Open a link")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(link, { link = it }, Modifier.weight(1f), placeholder = { Text("http(s), m3u8, rtsp, sftp, ftp…") }, singleLine = true)
            SButton({
                val u = link.trim()
                if (u.isNotEmpty()) {
                    if (u.substringBefore('?').endsWith(".m3u") || u.substringBefore('?').endsWith(".m3u8") && u.contains("playlist")) {
                        App.instance.store.update { x -> x.copy(sources = x.sources + Source(zone = Zone.INTERNET, proto = "m3u", name = Urls.nameOf(u), host = u)) }
                    } else {
                        App.instance.player.play(PlayItem(u, Urls.nameOf(u), isRadio = false))
                        nav.go(Screen.Player)
                    }
                }
            }, Modifier.padding(start = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text("Play") }
        }
        Text("A playlist link (.m3u) is saved below as an IPTV list.", color = C.muted, fontSize = 12.sp)
        SectionTitle("IPTV & playlists")
        if (lists.isEmpty()) Text("No playlists yet.", color = C.muted, fontSize = 14.sp)
        lists.forEach { s ->
            CardBox(Modifier.clickable {
                openList = s; channels = null; error = ""
                scope.launch { M3u.load(s.host).onSuccess { channels = it }.onFailure { error = it.message ?: "Could not load"; channels = emptyList() } }
            }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.host, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Reload list", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    SymButton({ App.instance.store.update { x -> x.copy(sources = x.sources.filterNot { it.id == s.id }) } }) { Text("✕", color = C.muted) }
                }
            }
        }
        if (openList != null) {
            SectionTitle(openList!!.name)
            if (error.isNotEmpty()) Text(error, color = C.bad)
            when (val ch = channels) {
                null -> CircularProgressIndicator(color = accent())
                else -> CardBox {
                    ch.take(500).forEach { c ->
                        StationRow(c.name, c.meta, fav = false, onFav = {}) {
                            App.instance.player.play(PlayItem(c.uri, c.name))
                            nav.go(Screen.Player)
                        }
                        Divider()
                    }
                }
            }
        }
        Box(Modifier.height(16.dp))
    }
}



/** Last listing of each folder seen (in memory only): a folder you return to shows at once, at the same place. */
object ListCache {
    private val map = object : LinkedHashMap<String, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Entry>>?) = size > 60
    }
    @Synchronized fun get(k: String): List<Entry>? = map[k]
    @Synchronized fun put(k: String, v: List<Entry>) { map[k] = v }
}


/** The phone's folder list as last read: shown at once when coming back (no empty flash, place kept). */
object InternalCache { @Volatile var folders: List<LocalFolder>? = null }


/** Read-only marks of a server's shares, and "Check write access" to test them again (and use them r/w if writable). */
@Composable
private fun WriteAccessRow(s: Source) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.readOnly.isEmpty()) "Write access: read / write" else "Read-only: " + s.readOnly.joinToString(", ") { if (it == "*") "whole server" else it },
                color = if (s.readOnly.isEmpty()) C.muted else C.bad, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.line, RoundedCornerShape(8.dp)).clickable(enabled = !busy) {
                busy = true; result = ""
                scope.launch { result = app.vlcinterface.sources.WriteGuard.check(s).joinToString("\n"); busy = false }
            }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (busy) CircularProgressIndicator(color = accent(), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Text("Check write access", color = accent(), fontSize = 12.sp)
            }
        }
        if (result.isNotEmpty()) Text(result, color = C.text, fontSize = 12.sp)
        if (s.readOnly.isNotEmpty()) Text("ReplayGain values and pictures for these shares are kept in the app.", color = C.muted, fontSize = 11.sp)
    }
}


/**
 * "+ Folder" on a LAN / Internet location (2026-11-21 16:57): its folders, from the top; tap one to go in, ‹ to go
 * up, "Add this folder" to add the folder shown — it becomes one of the location's folders (★, indexed for search).
 */
@Composable
private fun AddFolderDialog(src: app.vlcinterface.data.Source, onDone: () -> Unit) {
    val root = Urls.forSource(src)
    var uri by remember { mutableStateOf(root) }
    var dirs by remember { mutableStateOf<List<app.vlcinterface.sources.Entry>?>(null) }
    var err by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(uri) {
        dirs = null; err = ""
        Browse.list(src, uri).onSuccess { l -> dirs = l.filter { it.isDir }.sortedBy { it.name.lowercase() } }
            .onFailure { e -> dirs = emptyList(); err = "Could not open: " + SmbErrors.explain(e, src.user.isEmpty()) +
                (if (src.user.isEmpty()) " — if it needs a user name, use Save as preset first." else "") }
    }
    val path = android.net.Uri.decode(uri.substringAfter("://").substringAfter('/', "")).let { "/" + it.trimEnd('/') }
    val already = path in src.pins
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Add a folder · ${src.name.ifBlank { src.host }}", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uri.trimEnd('/') != root.trimEnd('/')) Text("‹", color = accent(), fontSize = 22.sp, modifier = Modifier.clickable {
                        uri = uri.trimEnd('/').substringBeforeLast('/') + "/"
                    }.padding(end = 10.dp))
                    Text(path, color = C.muted, fontSize = 12.sp, maxLines = 2)
                }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    when {
                        dirs == null -> CircularProgressIndicator(color = accent(), strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        err.isNotEmpty() -> Text(err, color = C.bad, fontSize = 13.sp)
                        dirs!!.isEmpty() -> Text("No folders inside.", color = C.muted, fontSize = 13.sp)
                        else -> dirs!!.forEach { d ->
                            Text("▤  " + d.name, color = C.text, fontSize = 15.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable { uri = d.uri.trimEnd('/') + "/" }.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton({
            if (!already) {
                // A location found on the LAN and not saved yet is saved now, with this folder.
                App.instance.store.update { st -> if (st.sources.any { it.id == src.id }) st.copy(sources = st.sources.map { if (it.id == src.id) it.copy(pins = it.pins + path) else it })
                    else st.copy(sources = st.sources + src.copy(pins = listOf(path))) }
                app.vlcinterface.sources.NetIndex.build(App.instance.store.current.sources.firstOrNull { it.id == src.id } ?: src, path)
                ShortNote.show("Added: " + path.trim('/').substringAfterLast('/').ifEmpty { path } + " — indexing for search")
            }
            onDone()
        }, enabled = !already && path != "/") { Text(if (already) "Already added" else "Add this folder", color = accent()) } },
        dismissButton = { STextButton(onDone) { Text("Cancel") } })
}
