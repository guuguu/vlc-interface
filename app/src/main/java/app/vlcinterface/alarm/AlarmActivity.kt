package app.vlcinterface.alarm

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GOLD = Color(0xFFE0B52E)
private val DARK = Color(0xFF1A1A1A)

/** The ringing screen: minimal (time, name, 3 snooze buttons, Stop → random pattern). Shown over the lock screen. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (AlarmEngine.ui.value.phase == "idle" && Alarms.active.isNotEmpty()) AlarmEngine.guardCheck(this)
        setContent { MaterialTheme(colorScheme = app.vlcinterface.ui.scheme(GOLD)) { AlarmScreen { finish() } } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && AlarmEngine.ui.value.phase == "ringing") {
            when (Alarms.current.global.volumeKeys) {
                "snooze" -> { AlarmEngine.snooze(this, 0); return true }
                "nothing" -> return true
                else -> AlarmEngine.userVolume = true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun AlarmScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    val u by AlarmEngine.ui.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(u.phase) { if (u.phase == "idle") { delay(400); close() } }
    var pattern by remember(u.phase) { mutableStateOf(false) }
    val g = Alarms.current.global
    fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(hm(now), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
            if (u.name.isNotEmpty()) Text(u.name, color = Color(0xFFB0B0B0), fontSize = 18.sp, textAlign = TextAlign.Center)
            when {
                pattern -> PatternPad { pattern = false; AlarmEngine.stop(ctx) }
                u.phase == "ringing" -> {
                    if (u.snoozesLeft > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        g.snoozes.forEachIndexed { i, m -> Btn("$m min", Modifier.weight(1f)) { AlarmEngine.snooze(ctx, i) } }
                    }
                    Btn("Stop", Modifier.fillMaxWidth(), filled = true) { if (g.pattern) pattern = true else AlarmEngine.stop(ctx) }
                }
                u.phase == "snoozed" -> {
                    Text("Snoozed until " + hm(u.until), color = GOLD, fontSize = 18.sp)
                    Btn("Dismiss alarm", Modifier.fillMaxWidth(), filled = true) { if (g.pattern) pattern = true else AlarmEngine.stop(ctx) }
                    Btn("Close", Modifier.fillMaxWidth()) { close() }
                }
                u.phase == "watching" -> {
                    Text("Wake check until " + hm(u.until), color = GOLD, fontSize = 18.sp)
                    Btn("I'm awake", Modifier.fillMaxWidth(), filled = true) { AlarmEngine.awake(ctx, "button") }
                    Btn("Close", Modifier.fillMaxWidth()) { close() }
                }
                u.phase == "confirming" -> {
                    Text("Are you awake?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("Rings again in ${((u.until - now) / 1000).coerceAtLeast(0)} s", color = GOLD, fontSize = 16.sp)
                    Btn("I'm awake", Modifier.fillMaxWidth(), filled = true) { AlarmEngine.awake(ctx, "button") }
                }
            }
        }
    }
}

@Composable
private fun Btn(text: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(if (filled) GOLD else DARK)
        .border(1.dp, if (filled) GOLD else Color(0xFF444444), RoundedCornerShape(28.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (filled) Color(0xFF1A0E05) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Random pattern: tap the numbered dots in order (a wrong dot gives a new pattern). */
@Composable
private fun PatternPad(onDone: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val seq = remember(attempt) { (0..8).shuffled().take(4) }
    var step by remember(attempt) { mutableIntStateOf(0) }
    Text("Tap the dots in order: 1 → 4", color = Color(0xFFB0B0B0), fontSize = 15.sp)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for (col in 0 until 3) {
                val i = r * 3 + col
                val n = seq.indexOf(i)
                val done = n in 0 until step
                Box(Modifier.size(68.dp).clip(CircleShape).background(if (done) GOLD else DARK).border(2.dp, if (n >= 0) GOLD else Color(0xFF444444), CircleShape)
                    .clickable {
                        if (n == step) { step++; if (step == seq.size) onDone() } else if (!done) attempt++
                    }, contentAlignment = Alignment.Center) {
                    if (n >= 0) Text("${n + 1}", color = if (done) Color(0xFF1A0E05) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
