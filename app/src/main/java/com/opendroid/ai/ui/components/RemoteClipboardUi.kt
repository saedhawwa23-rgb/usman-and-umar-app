package com.opendroid.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AppTheme

/**
 * A text field that sends what the user types to the desktop as a text input event.
 *
 * Typing goes through the protocol's `text` event. The agent uses a temporary clipboard paste for
 * Unicode and restores the desktop clipboard afterward. The field clears after each send.
 */
@Composable
fun RemoteKeyboardField(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    var text by remember { mutableStateOf("") }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type on the desktop") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (text.isNotEmpty()) {
                    onSend(text)
                    text = ""
                }
            },
            enabled = text.isNotEmpty(),
        ) { Text("SEND") }
    }
    Text(
        "Text is sent as a paste-style text event, so accents and non-Latin scripts work.",
        color = colors.textSecondary,
        fontSize = 11.sp,
    )
}

/**
 * Clipboard sync controls.
 *
 * [onPush] is called with the phone's clipboard text when the user taps SEND PHONE CLIPBOARD, and
 * with the desktop text when they tap COPY DESKTOP CLIPBOARD. Nothing is copied or read without a
 * tap unless the user turns sync on, and the toggle is explicit about that.
 */
@Composable
fun ClipboardSyncRow(
    desktopClipboard: String?,
    syncEnabled: Boolean,
    onPull: () -> Unit,
    onPush: (String) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Clipboard", color = colors.textPrimary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val local = clipboard.getText()?.text
                if (!local.isNullOrEmpty()) onPush(local)
            }) { Text("SEND PHONE CLIPBOARD") }
            OutlinedButton(onClick = onPull) { Text("GET DESKTOP CLIPBOARD") }
        }
        desktopClipboard?.let { text ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text.take(80) + if (text.length > 80) "…" else "",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                }) { Text("COPY") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = syncEnabled, onCheckedChange = onToggleSync)
            Text(
                "Auto-send phone clipboard on copy",
                color = colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
