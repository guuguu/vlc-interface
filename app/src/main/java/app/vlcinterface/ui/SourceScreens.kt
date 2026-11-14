package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.Source
import app.vlcinterface.data.Zone
import app.vlcinterface.sources.Browse
import app.vlcinterface.sources.ProbeResult
import app.vlcinterface.sources.SmbProbe
import app.vlcinterface.sources.Urls
import kotlinx.coroutines.launch

private val lanPresets = listOf("smb" to "SMB", "nfs" to "NFS", "sftp" to "SFTP", "ftp" to "FTP", "upnp" to "DLNA")
private val netPresets = listOf("sftp" to "SFTP", "webdav" to "WebDAV", "ftp" to "FTP", "ftps" to "FTPS", "http" to "HTTP")

@Composable
fun EditSourceScreen(nav: Navigator, e: Screen.EditSource) {
    val store = App.instance.store
    val existing = e.sourceId?.let { id -> store.current.sources.firstOrNull { it.id == id } }
    val start = existing ?: e.draft ?: Source(zone = e.zone, proto = if (e.zone == Zone.LAN) "smb" else "sftp")
    var s by remember { mutableStateOf(start) }
    var paste by remember { mutableStateOf("") }
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        BackHeader(if (existing != null) "Edit source" else "New source", nav)
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Preset")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (s.zone == Zone.LAN) lanPresets else netPresets).forEach { (p, l) ->
                    Pill(l, selected = s.proto == p) { s = s.copy(proto = p, port = 0) }
                }
            }
            if (s.proto == "smb") {
                CardBox {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste any path — \\\\host\\share, smb://, //host/share", color = C.muted, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(paste, { paste = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("\\\\nas\\media\\Movies") })
                            Button({
                                val p = SmbProbe.parse(paste)
                                if (p != null) nav.go(Screen.SmbDetect(paste, s))
                            }, Modifier.padding(start = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text("Detect") }
                        }
                        Text("Tries every SMB version, port and login type, then fills in the fields below. Put your user and password below first if the share needs them.", color = C.muted, fontSize = 12.sp)
                    }
                }
            }
            Field("Name", s.name) { s = s.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(2f)) {
                    Field(if (s.proto in listOf("webdav", "http")) "Address (https://host)" else if (s.proto == "upnp") "Server address (upnp://…)" else "Host or IP", s.host) { s = s.copy(host = it) }
                }
                if (s.proto != "upnp") Box(Modifier.weight(1f)) {
                    Field("Port", if (s.port > 0) s.port.toString() else s.defaultPort.toString(), KeyboardType.Number) { s = s.copy(port = it.toIntOrNull() ?: 0) }
                }
            }
            if (s.proto != "upnp") Field(if (s.proto == "smb") "Share / path" else "Path", s.path) { s = s.copy(path = it) }
            if (s.proto != "upnp" && s.proto != "nfs") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field("User", s.user) { s = s.copy(user = it) } }
                Box(Modifier.weight(1f)) { Field("Password", s.password, password = true) { s = s.copy(password = it) } }
            }
            if (s.proto == "smb") Field("Domain / workgroup (optional)", s.domain) { s = s.copy(domain = it) }
            if (s.smbVersion.isNotEmpty()) Text("Detected: ${s.smbVersion}", color = C.ok, fontSize = 13.sp)
            if (s.zone == Zone.INTERNET) ToggleRow("Wi-Fi only", "Don't use mobile data for this source", s.wifiOnly) { s = s.copy(wifiOnly = it) }
            if (test.isNotEmpty()) Text(test, color = if (test.startsWith("✓")) C.ok else C.bad, fontSize = 14.sp)
            Text("Passwords are stored encrypted on this phone.", color = C.muted, fontSize = 12.sp)
            Box(Modifier.height(8.dp))
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (existing != null) TextButton({
                store.update { st -> st.copy(sources = st.sources.filterNot { it.id == existing.id }) }; nav.back()
            }) { Text("Delete", color = C.bad) }
            OutlinedButton({
                testing = true; test = ""
                scope.launch {
                    val r = Browse.list(s, Urls.forSource(s))
                    test = r.fold({ "✓ Connected · ${it.size} items" }, { "✕ ${it.message}" }); testing = false
                }
            }, Modifier.weight(1f)) { if (testing) CircularProgressIndicator(Modifier.height(18.dp), color = accent()) else Text("Test connection") }
            Button({
                val final = s.copy(name = s.name.ifBlank { Urls.hostOnly(s.host).ifEmpty { s.host } })
                store.update { st -> st.copy(sources = st.sources.filterNot { it.id == final.id } + final) }
                nav.back()
            }, Modifier.weight(1f), enabled = s.host.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text("Save preset") }
        }
    }
}

@Composable
fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
fun SmbDetectScreen(nav: Navigator, d: Screen.SmbDetect) {
    val path = remember { SmbProbe.parse(d.input) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    LaunchedEffect(d.input) {
        if (path != null) result = SmbProbe.probe(path, d.draft.user, d.draft.password, d.draft.domain)
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Detecting SMB setup", nav, d.input)
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val r = result
            if (path == null) Text("That doesn't look like an SMB path.", color = C.bad)
            else if (r == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp), color = accent()); Text("  Testing versions, ports and logins…", color = C.muted)
            } else {
                r.steps.groupBy { it.group }.forEach { (g, steps) ->
                    SectionTitle(g)
                    CardBox {
                        steps.forEach { st ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Badge(if (st.ok) "✓" else "✕", st.ok)
                                Text(st.label, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                Text(st.note, color = C.muted, fontSize = 12.sp)
                            }
                            Divider()
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, if (r.works) accent() else C.bad, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Text(if (r.works) "BEST WORKING SETUP" else "NOT WORKING YET", color = if (r.works) accent() else C.bad, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(r.summary, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (r.works && r.bestVersion == "SMB1") Text("This server only speaks SMB1. Browsing works; playback depends on libVLC's older SMB1 support.", color = C.bad, fontSize = 12.sp)
                }
            }
            Box(Modifier.height(8.dp))
        }
        val r = result
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ nav.back() }, Modifier.weight(1f)) { Text("Back to fields") }
            Button({
                if (path != null && r != null) {
                    val src = d.draft.copy(
                        proto = "smb", host = path.host, path = path.folder, smbVersion = r.bestVersion,
                        user = if (r.guest) "" else d.draft.user, password = if (r.guest) "" else d.draft.password,
                        name = d.draft.name.ifBlank { path.host + " · " + path.share },
                    )
                    App.instance.store.update { st -> st.copy(sources = st.sources.filterNot { it.id == src.id } + src) }
                    nav.home(); nav.replace(Screen.Library(1))
                }
            }, Modifier.weight(1f), enabled = r?.works == true, colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text("Use & save") }
        }
    }
}
