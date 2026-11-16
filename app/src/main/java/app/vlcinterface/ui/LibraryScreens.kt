package app.vlcinterface.ui

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
    Row(Modifier.fillMaxWidth().padding(start = if (canBack) 4.dp else 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canBack) BackButton(nav!!)
        Text(title, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        OutputPill(openOutput)
        actions()
    }
}

@Composable
fun HomeButton(nav: Navigator, tint: Color = C.text) {
    SymButton({ nav.home(); nav.replace(Screen.Library()) }) { SymIcon(Icons.Filled.Home, "Home", tint = tint) }
}

@Composable
fun BackHeader(title: String, nav: Navigator, sub: String = "", afterHome: @Composable () -> Unit = {}, actions: @Composable () -> Unit = {}) = Scaled {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SymButton(onClick = { nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
        HomeButton(nav)
        afterHome()
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
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
    var folders by remember { mutableStateOf<List<LocalFolder>?>(null) }
    var busy by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<LocalFolder?>(null) }
    var addFolder by remember { mutableStateOf<LocalFolder?>(null) }
    var rgFolder by remember { mutableStateOf<LocalFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<Pair<LocalFolder, List<Entry>>?>(null) }
    var blackFolder by remember { mutableStateOf<LocalFolder?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { folders = InternalSource.folders(ctx) }
    val scroll = rememberScrollState()
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
                            onRemove = { blackFolder = f })
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
    val scroll = rememberScrollState()
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
                            Pill("Save as preset") {
                                val proto = e.uri.substringBefore("://").lowercase()
                                val draft = if (proto == "upnp") Source(zone = Zone.LAN, proto = "upnp", name = e.name, host = e.uri)
                                else Source(zone = Zone.LAN, proto = proto, name = e.name, host = Urls.hostOnly(e.uri.substringAfter("://")))
                                nav.go(Screen.EditSource(Zone.LAN, draft = draft))
                            }
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
            Box(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.line, RoundedCornerShape(8.dp)).clickable {
                App.instance.store.update { st -> st.copy(sources = st.sources.map { if (it.id == s.id) it.copy(lastScan = System.currentTimeMillis()) else it }) }
                app.vlcinterface.sources.NetIndex.rebuild(s)          // its pinned folders' index too
                nav.go(Screen.Browse(s.id, Urls.forSource(s), s.name, highlight = "\u0000rescan"))
            }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text("↻ Rescan", color = a, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
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
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search stations by name") }, singleLine = true)
            SymButton({ run(name = q) }) { SymIcon(Icons.Filled.Search, "Search", tint = C.text) }
        }
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
                    results!!.forEach { r ->
                        val fav = st.stations.any { it.url == r.url }
                        StationRow(r.name, r.meta, fav, onFav = {
                            App.instance.store.update { x ->
                                if (fav) x.copy(stations = x.stations.filterNot { it.url == r.url })
                                else x.copy(stations = x.stations + Station(name = r.name, url = r.url, meta = r.meta, favicon = r.favicon))
                            }
                        }) { play(r.name, r.url) }
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
fun StationRow(name: String, meta: String, fav: Boolean, onFav: () -> Unit, onLong: (() -> Unit)? = null, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).combinedClickable(onClick = onPlay, onLongClick = onLong).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val scroll = rememberScrollState()
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
