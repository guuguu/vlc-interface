package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app's dialog window, used everywhere in place of Material's AlertDialog (same parameters).
 * It always sits in the space above the keyboard: when the keyboard opens, the window moves up and its content
 * shrinks (lists scroll) so nothing is ever under the keyboard.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = C.panel,
) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Tapping outside closes it, like a normal dialog.
        Box(Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Column(modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(containerColor)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CompositionLocalProvider(LocalContentColor provides C.text) {
                    icon?.let { Box(Modifier.align(Alignment.CenterHorizontally)) { it() } }
                    title?.let { ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(color = C.text)) { it() } }
                    // The content takes what is left and shrinks first when space gets short.
                    text?.let {
                        Box(Modifier.weight(1f, fill = false)) {
                            CompositionLocalProvider(LocalContentColor provides C.muted) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = C.muted)) { it() }
                            }
                        }
                    }
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
