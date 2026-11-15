package com.opendroid.ai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.RemoteControlViewModel

/**
 * Owner-facing device control: pair a controlling device, list and remove paired devices, and
 * engage the kill switch.
 *
 * This screen pairs and manages devices only. It does not open an inbound port and does not use
 * a public relay; carrying a request to this device needs the owner's own private network or
 * tunnel. Controlling this phone's own apps runs through the accessibility service and is not
 * started from here.
 */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var codeEntry by remember { mutableStateOf("") }
    val colors = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("REMOTE DEVICE CONTROL", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pair a device you own, then approve each action it asks this phone to perform. " +
                "The default answer to every request is Deny.",
            color = colors.textSecondary,
            fontSize = 13.sp
        )

        SectionCard(title = "Pair this device") {
            Text(
                "Start pairing and read the code out to the controlling device. The code is " +
                    "single-use and expires after about 5 minutes.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Button(
                onClick = { viewModel.startPairing() },
                enabled = !state.killSwitchActive
            ) { Text("START PAIRING") }

            state.pairingCode?.let { code ->
                Text("Pairing code", color = colors.textSecondary, fontSize = 12.sp)
                Text(code, color = colors.accentCyan, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Expires in about 5 minutes.", color = colors.textSecondary, fontSize = 11.sp)
            }

            Text(
                "Have a code from another device? Enter it here to pair with that device.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = codeEntry,
                onValueChange = { codeEntry = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing code") },
                singleLine = true
            )
            Button(
                onClick = { viewModel.completePairing(codeEntry); codeEntry = "" },
                enabled = codeEntry.length == 6 && !state.killSwitchActive
            ) { Text("PAIR") }
        }

        SectionCard(title = "Paired devices") {
            if (state.pairedDevices.isEmpty()) {
                Text("No devices are paired yet.", color = colors.textSecondary, fontSize = 12.sp)
            } else {
                state.pairedDevices.forEach { keyId ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(keyId, color = colors.textPrimary, fontSize = 13.sp)
                        TextButton(onClick = { viewModel.unpair(keyId) }) { Text("UNPAIR") }
                    }
                }
            }
        }

        SectionCard(title = "Kill switch") {
            Text(
                if (state.killSwitchActive) "Remote control is disabled. Open sessions are closed."
                else "Engaging the kill switch disables remote control immediately and closes open sessions.",
                color = if (state.killSwitchActive) colors.accentRed else colors.textSecondary,
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = { viewModel.engageKillSwitch() },
                enabled = !state.killSwitchActive
            ) { Text("ENGAGE KILL SWITCH") }
        }

        state.message?.let { message ->
            Text(
                message,
                color = if (state.messageIsError) colors.accentRed else colors.accentGreenButton,
                fontSize = 13.sp
            )
        }

        Text(
            "What this does not do: this screen cannot reach a Windows, macOS or Linux computer. " +
                "The app has no agent to install on a PC or desktop, so controlling one is not " +
                "supported. Requests also need a transport you provide (your own private network " +
                "or tunnel); the app never opens an inbound port and uses no public relay.",
            color = colors.textSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
