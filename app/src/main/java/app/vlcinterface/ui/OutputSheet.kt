package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.player.OutputKind

/** Sound output picker: phone speaker, wired headphones, Bluetooth devices. */
@Composable
fun OutputSheet(onDismiss: () -> Unit) {
    val router = App.instance.output
    val store = App.instance.store
    val devices by router.devices.collectAsState()
    val st by store.state.collectAsState()
    var vol by remember { mutableIntStateOf(router.volumePercent()) }
    LaunchedEffect(Unit) { router.refresh() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = C.popup) {
        val scroll = rememberScrollState()
        Column(Modifier.scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SymButton(onDismiss) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
                Text("Sound output", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            CardBox {
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                        if (!d.active) router.select(d)
                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val a = accent()
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (d.active) a.copy(alpha = 0.16f) else C.card2), contentAlignment = Alignment.Center) {
                            Text(when (d.kind) { OutputKind.SPEAKER -> "🔈"; OutputKind.BLUETOOTH -> "BT"; else -> "🎧" }, color = if (d.active) a else C.text)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(d.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (d.active) "Playing here · ${d.detail}" else d.detail, color = C.muted, fontSize = 12.sp)
                        }
                        if (d.connected) Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (d.active) a else Color(0xFF5A606B), CircleShape), contentAlignment = Alignment.Center) {
                            if (d.active) Box(Modifier.size(10.dp).clip(CircleShape).background(a))
                        } else Pill("Connect") { router.openBluetoothSettings() }
                    }
                    Divider()
                }
                Text("+  Pair a new Bluetooth device", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { router.openBluetoothSettings() }.padding(16.dp))
            }
            Text("Tap a device to switch with Android's output switcher. Android plays media on one Bluetooth device at a time.",
                color = C.muted, fontSize = 12.sp)
            devices.firstOrNull { it.active }?.let { d ->
                Text("Preamp and balance for ${d.name}", color = C.muted, fontSize = 13.sp)
                DeviceSoundControls(d.name)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = C.muted, fontSize = 13.sp)
                Slider(vol.toFloat(), { vol = it.toInt(); router.setVolumePercent(vol) }, valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
                Text("$vol%", color = C.text, fontSize = 13.sp)
            }
            CardBox {
                ToggleRow("Remember last output per device", "Reconnect a device and it is picked again", st.settings.rememberOutputPerDevice) { v ->
                    store.update { it.copy(settings = it.settings.copy(rememberOutputPerDevice = v)) }
                }
                Divider()
                ToggleRow("Pause when output disconnects", "Headphones unplugged or Bluetooth lost", st.settings.pauseOnDisconnect) { v ->
                    store.update { it.copy(settings = it.settings.copy(pauseOnDisconnect = v)) }
                }
            }
            Box(Modifier.heightIn(min = 16.dp))
        }
    }
}
