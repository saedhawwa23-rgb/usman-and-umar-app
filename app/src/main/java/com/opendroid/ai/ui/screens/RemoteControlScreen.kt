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
 * Owner-facing device control: pair a controlling device, run the LAN listener, approve or deny
 * inbound actions, and send signed actions to a paired device.
 *
 * The listener is off until the owner turns it on and is meant for a trusted private network
 * only: the payload is signed, but the LAN traffic is not encrypted. It does not use a public
 * relay. Controlling a Windows, macOS or Linux computer is not supported - there is no agent to
 * install on one.
 */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var codeEntry by remember { mutableStateOf("") }
    var transferCode by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
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

        state.pendingApproval?.let { pending ->
            SectionCard(title = "Remote action needs your approval") {
                Text(pending.summary, color = colors.textPrimary, fontSize = 13.sp)
                Text("Risk: ${pending.risk.name}", color = colors.textSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.approvePending() }) { Text("APPROVE") }
                    OutlinedButton(onClick = { viewModel.denyPending() }) { Text("DENY") }
                }
            }
        }

        SectionCard(title = "Connection (LAN)") {
            Text(
                if (state.listening)
                    "Listening on port ${state.listenPort ?: "?"}. A paired device on this network can reach this phone."
                else "Not listening. Turn this on so a paired device on this network can reach this phone.",
                color = if (state.listening) colors.accentGreenButton else colors.textSecondary,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.startListening() },
                    enabled = !state.listening && !state.killSwitchActive
                ) { Text("START LISTENING") }
                OutlinedButton(
                    onClick = { viewModel.stopListening() },
                    enabled = state.listening
                ) { Text("STOP") }
            }
            Text(
                "Use this only on a trusted network. The request is signed, but the connection " +
                    "itself is not encrypted. No public relay is used.",
                color = colors.textSecondary,
                fontSize = 11.sp
            )
        }

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
                        Row {
                            TextButton(onClick = { viewModel.exportTransferCode(keyId) }) { Text("EXPORT") }
                            TextButton(onClick = { viewModel.unpair(keyId) }) { Text("UNPAIR") }
                        }
                    }
                }
                Text(
                    "To control another phone, copy its transfer code here (the other phone shows " +
                        "it from its own Remote screen).",
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = transferCode,
                    onValueChange = { transferCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transfer code from the other device") }
                )
                Button(
                    onClick = { viewModel.importTransferCode(transferCode); transferCode = "" },
                    enabled = transferCode.isNotBlank()
                ) { Text("IMPORT TRANSFER CODE") }
            }
        }

        SectionCard(title = "Send an action to a paired device") {
            Text(
                "Enter the paired device's LAN address and the tool to run. The request is signed " +
                    "with the shared key before it is sent.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device address (e.g. 192.168.1.20)") },
                singleLine = true
            )
            OutlinedButton(
                onClick = { viewModel.sendAction(host, "battery_status", "{}") },
                enabled = host.isNotBlank() && state.pairedDevices.isNotEmpty()
            ) { Text("SEND: BATTERY STATUS") }
            OutlinedButton(
                onClick = { viewModel.sendAction(host, "network_status", "{}") },
                enabled = host.isNotBlank() && state.pairedDevices.isNotEmpty()
            ) { Text("SEND: NETWORK STATUS") }
            if (state.pairedDevices.isEmpty()) {
                Text(
                    "Pair a device, or import its transfer code, before sending.",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
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
