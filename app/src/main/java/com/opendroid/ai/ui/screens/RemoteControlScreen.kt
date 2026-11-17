package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.remote.RemoteSessionState
import com.opendroid.ai.remote.protocol.FileEntry
import com.opendroid.ai.ui.components.BrandOutlineButton
import com.opendroid.ai.ui.components.EmptyState
import com.opendroid.ai.ui.components.LiveDot
import com.opendroid.ai.ui.components.MetricTile
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.SectionHeading
import com.opendroid.ai.ui.components.SegmentedTabs
import com.opendroid.ai.ui.components.StatusPill
import com.opendroid.ai.ui.components.PlatformTab
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.RemoteControlViewModel

/**
 * Owner-facing device control: pair a controlling device, run the LAN listener, approve or deny
 * inbound actions, send signed actions to a paired device, and drive a computer that runs the
 * desktop companion agent.
 *
 * The LAN listener is off until the owner turns it on and is meant for a trusted private network
 * only: the payload is signed, but the LAN traffic is not encrypted. It does not use a public
 * relay.
 *
 * Controlling a computer works through the desktop agent (see `desktop-agent/`). The agent must be
 * started on the computer first and prints a pairing code; the code is entered here once. After
 * that the phone reconnects with a stored secret.
 */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var codeEntry by remember { mutableStateOf("") }
    var transferCode by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val colors = AppTheme.colors

    val activeSessions = state.sessions.count { it.isLive }
    val gatewayStatus = when {
        state.killSwitchActive -> "Off" to PlatformStatus.ERROR
        state.listening -> "On" to PlatformStatus.HEALTHY
        else -> "Off" to PlatformStatus.IDLE
    }
    val deviceStatus = if (state.desktopConnected) "Connected" to PlatformStatus.HEALTHY
    else "Offline" to PlatformStatus.IDLE

    Column(Modifier.fillMaxSize()) {
        BrandedRemoteHeader(activeSessions = activeSessions)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Overview metrics: the real, live numbers behind the dashboard.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(activeSessions.toString(), "Live sessions", colors.electricBlue, Modifier.weight(1f))
                MetricTile(state.pairedDevices.size.toString(), "Paired phones", colors.emerald, Modifier.weight(1f))
                MetricTile(state.pairedDesktops.size.toString(), "Computers", colors.accentCyan, Modifier.weight(1f))
            }

            SegmentedTabs(
                tabs = listOf(
                    PlatformTab("Overview"),
                    PlatformTab("Devices", activeSessions),
                    PlatformTab("Transfer"),
                    PlatformTab("Terminal"),
                    PlatformTab("Gateway"),
                ),
                selectedIndex = tab,
                onSelect = { tab = it },
            )

            when (tab) {
                0 -> RemoteOverviewTab(state, viewModel, codeEntry, { codeEntry = it }, host, { host = it })
                1 -> RemoteDevicesTab(state, viewModel, transferCode, { transferCode = it })
                2 -> RemoteTransferTab(state, viewModel)
                3 -> RemoteTerminalTab(state, viewModel)
                else -> RemoteGatewayTab(state, viewModel, gatewayStatus, deviceStatus)
            }

            state.message?.let { message ->
                StatusPill(
                    text = message,
                    status = if (state.messageIsError) PlatformStatus.ERROR else PlatformStatus.HEALTHY,
                )
            }
        }
    }
}

/** The premium navy gradient header with a live connection indicator. */
@Composable
private fun BrandedRemoteHeader(activeSessions: Int) {
    val colors = AppTheme.colors
    com.opendroid.ai.ui.components.BrandHeader(
        title = "Universal Remote",
        subtitle = if (activeSessions > 0) "$activeSessions live session(s)" else "Pair, control and transfer",
        icon = Icons.Filled.PhoneAndroid,
        trailing = { LiveDot(if (activeSessions > 0) PlatformStatus.ACTIVE else PlatformStatus.IDLE) },
    )
}

@Composable
private fun RemoteOverviewTab(
    state: RemoteControlViewModel.UiState,
    viewModel: RemoteControlViewModel,
    codeEntry: String,
    onCodeEntry: (String) -> Unit,
    host: String,
    onHost: (String) -> Unit,
) {
    val colors = AppTheme.colors

    state.pendingApproval?.let { pending ->
        PlatformCard(
            title = "Remote action needs approval",
            subtitle = "The default answer to every request is Deny.",
            status = "Action" to PlatformStatus.WARNING,
        ) {
            Text(pending.summary, color = colors.textPrimary, fontSize = 13.sp)
            Text("Risk: ${pending.risk.name}", color = colors.textSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.approvePending() }) { Text("APPROVE") }
                OutlinedButton(onClick = { viewModel.denyPending() }) { Text("DENY") }
            }
        }
    }

    PlatformCard(
        title = "Pair this phone",
        subtitle = "Read the code out to the controlling device",
        status = if (state.pairingCode != null) "Code live" to PlatformStatus.ACTIVE else "Idle" to PlatformStatus.IDLE,
    ) {
        Button(onClick = { viewModel.startPairing() }, enabled = !state.killSwitchActive) { Text("START PAIRING") }
        state.pairingCode?.let { code ->
            Text("Pairing code", color = colors.textSecondary, fontSize = 12.sp)
            Text(code, color = colors.accentCyan, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Single-use, expires after about 5 minutes.",
                color = colors.textSecondary, fontSize = 11.sp,
            )
        }
        OutlinedTextField(
            value = codeEntry,
            onValueChange = { onCodeEntry(it.filter(Char::isDigit).take(6)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter a code from another device") },
            singleLine = true,
        )
        Button(
            onClick = { viewModel.completePairing(codeEntry); onCodeEntry("") },
            enabled = codeEntry.length == 6 && !state.killSwitchActive,
        ) { Text("PAIR") }
    }

    PlatformCard(
        title = "Active sessions",
        status = "${state.sessions.count { it.isLive }} live" to
            if (state.sessions.any { it.isLive }) PlatformStatus.HEALTHY else PlatformStatus.IDLE,
    ) {
        if (state.sessions.isEmpty()) {
            EmptyState(
                Icons.Filled.PhoneAndroid,
                "No sessions yet",
                "A session starts when a device is paired and connected.",
            )
        } else {
            state.sessions.forEach { session ->
                val status = when (session.state) {
                    RemoteSessionState.CONNECTED -> PlatformStatus.HEALTHY
                    RemoteSessionState.RECONNECTING -> PlatformStatus.ACTIVE
                    RemoteSessionState.PAIRING -> PlatformStatus.INFO
                    else -> PlatformStatus.ERROR
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(session.deviceId, color = colors.textPrimary, fontSize = 13.sp)
                        StatusPill(session.state.name, status)
                    }
                    if (session.isLive) {
                        TextButton(onClick = { viewModel.disconnectSession(session.deviceId) }) { Text("DISCONNECT") }
                    }
                }
            }
        }
    }

    PlatformCard(title = "Send an action", subtitle = "Signed with the shared key before it is sent") {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device address (e.g. 192.168.1.20)") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandOutlineButton(
                text = "Battery",
                onClick = { viewModel.sendAction(host, "battery_status", "{}") },
                enabled = host.isNotBlank() && state.pairedDevices.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
            BrandOutlineButton(
                text = "Network",
                onClick = { viewModel.sendAction(host, "network_status", "{}") },
                enabled = host.isNotBlank() && state.pairedDevices.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RemoteDevicesTab(
    state: RemoteControlViewModel.UiState,
    viewModel: RemoteControlViewModel,
    transferCode: String,
    onTransferCode: (String) -> Unit,
) {
    val colors = AppTheme.colors
    PlatformCard(title = "Paired phones", subtitle = "Keys this phone can sign for") {
        if (state.pairedDevices.isEmpty()) {
            EmptyState(Icons.Filled.PhoneAndroid, "No devices paired", "Pair a device or import a transfer code.")
        } else {
            state.pairedDevices.forEach { keyId ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(keyId, color = colors.textPrimary, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = { viewModel.exportTransferCode(keyId) }) { Text("EXPORT") }
                        TextButton(onClick = { viewModel.unpair(keyId) }) { Text("UNPAIR") }
                    }
                }
            }
        }
        OutlinedTextField(
            value = transferCode,
            onValueChange = onTransferCode,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Transfer code from the other device") },
        )
        Button(
            onClick = { viewModel.importTransferCode(transferCode); onTransferCode("") },
            enabled = transferCode.isNotBlank(),
        ) { Text("IMPORT TRANSFER CODE") }
    }
}

@Composable
private fun RemoteTransferTab(state: RemoteControlViewModel.UiState, viewModel: RemoteControlViewModel) {
    val colors = AppTheme.colors
    PlatformCard(
        title = "Secure file transfer",
        subtitle = "Files travel over the authenticated desktop session",
        status = if (state.desktopConnected) "Connected" to PlatformStatus.HEALTHY else "Offline" to PlatformStatus.IDLE,
    ) {
        Text(
            "Connect a computer in the Gateway tab, then browse its shared folder here. Downloads " +
                "land in this app's storage; nothing is sent to a third party.",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        var path by remember { mutableStateOf(state.remotePath) }
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Folder on the computer (blank = root)") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandOutlineButton(
                text = "Browse",
                onClick = { viewModel.listRemoteFiles(path) },
                enabled = state.desktopConnected,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.remoteEntries.isEmpty()) {
            EmptyState(Icons.Filled.Computer, "No folder listed", "Browse a folder on a connected computer.")
        } else {
            SectionHeading("In ${state.remotePath}", "${state.remoteEntries.size} item(s)")
            LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                items(state.remoteEntries) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (entry.directory) "📁 " else "📄 ") + entry.name,
                                color = colors.textPrimary, fontSize = 13.sp,
                            )
                            if (!entry.directory) {
                                Text("${entry.size / 1024} KB", color = colors.textSecondary, fontSize = 11.sp)
                            }
                        }
                        if (entry.directory) {
                            TextButton(onClick = { viewModel.listRemoteFiles(entry.name) }) { Text("OPEN") }
                        } else {
                            TextButton(onClick = { viewModel.downloadRemoteFile(entry.name) }) { Text("DOWNLOAD") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteTerminalTab(state: RemoteControlViewModel.UiState, viewModel: RemoteControlViewModel) {
    val colors = AppTheme.colors
    var command by remember { mutableStateOf("") }
    PlatformCard(
        title = "Authorized remote terminal",
        subtitle = "Runs on this phone only after your approval",
        status = if (state.pairedDevices.isNotEmpty()) "Ready" to PlatformStatus.ACTIVE else "No device" to PlatformStatus.WARNING,
    ) {
        Text(
            "Send a single Linux command to this phone as a signed remote action. The approval " +
                "prompt appears first; chaining and redirection are blocked by the tool policy.",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Command (e.g. uname -a)") },
            singleLine = true,
        )
        BrandOutlineButton(
            text = "Run",
            onClick = { viewModel.runRemoteTerminal(command) },
            enabled = command.isNotBlank() && state.pairedDevices.isNotEmpty(),
        )
        state.terminalOutput?.let { output ->
            SectionHeading("Output")
            Text(output, color = colors.textPrimary, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

@Composable
private fun RemoteGatewayTab(
    state: RemoteControlViewModel.UiState,
    viewModel: RemoteControlViewModel,
    gatewayStatus: Pair<String, PlatformStatus>,
    deviceStatus: Pair<String, PlatformStatus>,
) {
    val colors = AppTheme.colors
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8777") }
    var code by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("1280") }

    PlatformCard(
        title = "Remote gateway (LAN listener)",
        subtitle = "No public relay; signed payloads only",
        status = gatewayStatus,
    ) {
        Text(
            if (state.listening)
                "Listening on port ${state.listenPort ?: "?"}. A paired device on this network can reach this phone."
            else "Not listening. Turn this on so a paired device on this network can reach this phone.",
            color = if (state.listening) colors.emerald else colors.textSecondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.startListening() },
                enabled = !state.listening && !state.killSwitchActive,
            ) { Text("START LISTENING") }
            OutlinedButton(onClick = { viewModel.stopListening() }, enabled = state.listening) { Text("STOP") }
        }
        Text(
            "The request is signed, but the LAN connection itself is not encrypted. Use only on a trusted network.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }

    PlatformCard(title = "Computer (desktop agent)", status = deviceStatus) {
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Computer address (e.g. 192.168.1.20)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it.uppercase() },
                label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.pairWithDesktop(host, port.toIntOrNull() ?: 8777, code) },
                enabled = host.isNotBlank() && code.isNotBlank() && !state.desktopConnected,
            ) { Text("PAIR") }
            OutlinedButton(
                onClick = { viewModel.connectToDesktop(host, port.toIntOrNull() ?: 8777) },
                enabled = host.isNotBlank() && !state.desktopConnected,
            ) { Text("CONNECT") }
            OutlinedButton(onClick = { viewModel.disconnectDesktop() }, enabled = state.desktopConnected) {
                Text("DISCONNECT")
            }
        }
        if (state.pairedDesktops.isNotEmpty()) {
            Text(
                "Paired: " + state.pairedDesktops.joinToString { "${it.serverName} (${it.host}:${it.port})" },
                color = colors.textSecondary, fontSize = 12.sp,
            )
        }
        if (state.desktopConnected) {
            Text("Connected to ${state.desktopName ?: "the desktop"}.", color = colors.emerald, fontSize = 12.sp)
            OutlinedTextField(
                value = width, onValueChange = { width = it.filter(Char::isDigit) },
                label = { Text("Screen width in pixels") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.desktopStreaming) {
                    OutlinedButton(onClick = { viewModel.stopDesktopStream() }) { Text("STOP VIEWING") }
                } else {
                    Button(onClick = { viewModel.startDesktopStream(width.toIntOrNull() ?: 1280, 60) }) {
                        Text("VIEW SCREEN")
                    }
                }
                OutlinedButton(onClick = { viewModel.listDesktopApplications() }) { Text("LIST APPS") }
            }
            state.lastScreenFrame?.let { frame ->
                Text(
                    "Last frame: ${frame.width}x${frame.height} ${frame.format}, ${frame.data.size / 1024} KB",
                    color = colors.textSecondary, fontSize = 12.sp,
                )
            }
            state.desktopApplications.take(10).forEach { app ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.name, color = colors.textPrimary, fontSize = 12.sp)
                    TextButton(onClick = { viewModel.launchDesktopApplication(app.id) }) { Text("START") }
                }
            }
        }
        if (state.pairedDesktops.isNotEmpty()) {
            OutlinedButton(onClick = { viewModel.forgetDesktops() }) { Text("FORGET PAIRED COMPUTERS") }
        }
    }

    PlatformCard(
        title = "Kill switch",
        subtitle = "Disables remote control immediately and closes open sessions",
        status = if (state.killSwitchActive) "Engaged" to PlatformStatus.ERROR else "Ready" to PlatformStatus.HEALTHY,
    ) {
        OutlinedButton(
            onClick = { viewModel.engageKillSwitch() },
            enabled = !state.killSwitchActive,
        ) { Text("ENGAGE KILL SWITCH") }
    }
}
