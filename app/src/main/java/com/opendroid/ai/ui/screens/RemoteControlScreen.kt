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
import androidx.compose.material3.Switch
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.remote.PairedDesktop
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
import com.opendroid.ai.ui.components.ClipboardSyncRow
import com.opendroid.ai.ui.components.PlatformTab
import com.opendroid.ai.ui.components.RemoteDesktopView
import com.opendroid.ai.ui.components.RemoteKeyboardField
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
                    PlatformTab("Internet"),
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
                4 -> RemoteInternetSharingTab(state, viewModel)
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
            SectionHeading("In ${state.remotePath}", subtitle = "${state.remoteEntries.size} item(s)")
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
    var fingerprint by remember { mutableStateOf("") }

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
        OutlinedTextField(
            value = fingerprint, onValueChange = { fingerprint = it },
            label = { Text("Desktop certificate SHA-256 (for a secure connection)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (fingerprint.isBlank()) {
                "Without a fingerprint the session uses plain TCP. That is fine on a trusted local " +
                    "network, but use the fingerprint the desktop printed before connecting over the internet."
            } else {
                "The desktop certificate will be pinned to this fingerprint, so an impostor cannot " +
                    "complete the connection."
            },
            color = colors.textSecondary, fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.pairWithDesktop(
                        host,
                        port.toIntOrNull() ?: 8777,
                        code,
                        fingerprint.takeIf { it.isNotBlank() },
                    )
                },
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
            if (state.desktopStreaming || state.lastScreenFrame != null) {
                RemoteDesktopView(
                    frame = state.lastScreenFrame,
                    onInput = { viewModel.sendDesktopInput(it) },
                )
                Text(
                    "Tap to click, drag to move, long press for right click.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
                RemoteKeyboardField(onSend = { text ->
                    viewModel.sendDesktopInput(
                        com.opendroid.ai.remote.protocol.InputEvent(
                            com.opendroid.ai.remote.protocol.InputEvent.TEXT,
                            text = text,
                        )
                    )
                })
            }
            ClipboardSyncRow(
                desktopClipboard = state.desktopClipboard,
                syncEnabled = state.clipboardSyncEnabled,
                onPull = { viewModel.requestDesktopClipboard() },
                onPush = { viewModel.sendToDesktopClipboard(it) },
                onToggleSync = { viewModel.setClipboardSyncEnabled(it) },
            )
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

/**
 * Internet sharing: send this phone's traffic out through a paired computer's connection.
 *
 * The proxy is loopback only, so the address shown here cannot be reached from the network the
 * phone is on. That is deliberate: it means only this phone can use the tunnel.
 */
/**
 * Full-tunnel gateway controls.
 *
 * This is the mode that makes every app leave through the computer rather than only a browser
 * pointed at a proxy. It needs Android's VPN consent, so the first attempt launches the system
 * dialog and the same button retries afterwards.
 */
@Composable
private fun GatewayCard(
    state: RemoteControlViewModel.UiState,
    viewModel: RemoteControlViewModel,
    desktop: PairedDesktop?,
) {
    val colors = AppTheme.colors
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            desktop?.let { viewModel.startGateway(it, state.gatewayFullTunnel) }
        } else {
            viewModel.clearGatewayError()
        }
    }

    PlatformCard(
        title = "Send all traffic through the computer",
        subtitle = "Full-tunnel mode for every app, not just a browser",
        status = if (state.gatewayRunning) "On" to PlatformStatus.HEALTHY else "Off" to PlatformStatus.IDLE,
    ) {
        Text(
            "With this on, Android routes every app through a private tunnel to the paired " +
                "computer, which forwards it to the internet. Use it when this phone has no usable " +
                "connection of its own.",
            color = colors.textSecondary, fontSize = 12.sp,
        )

        if (desktop == null) {
            Text(
                "Pair a computer in the Gateway tab first.",
                color = colors.textSecondary, fontSize = 12.sp,
            )
            return@PlatformCard
        }

        if (desktop.gatewayPublicKey.isNullOrBlank()) {
            Text(
                "This computer has not offered a gateway key yet. The browser proxy above still " +
                    "works; full-tunnel mode needs the updated desktop agent.",
                color = colors.accentRed, fontSize = 12.sp,
            )
            return@PlatformCard
        }

        if (state.gatewayRunning) {
            SectionHeading("Tunnel", subtitle = "Live counters from the tunnel")
            Text(
                "Through ${state.gatewayDesktopName ?: desktop.serverName} at ${state.gatewayEndpoint ?: "…"}",
                color = colors.electricBlue, fontSize = 13.sp,
            )
            val seconds = ((System.currentTimeMillis() - state.gatewayConnectedSinceMs) / 1000).coerceAtLeast(0)
            Text(
                "Up ${formatDuration(seconds)} · down ${state.gatewayBytesIn / 1024} KB · " +
                    "up ${state.gatewayBytesOut / 1024} KB",
                color = colors.textSecondary, fontSize = 11.sp,
            )
            Text(
                if (state.gatewayLastHandshakeMs > 0) {
                    "The computer answered ${formatDuration((System.currentTimeMillis() - state.gatewayLastHandshakeMs) / 1000)} ago."
                } else {
                    "Waiting for the first reply from the computer."
                },
                color = colors.textSecondary, fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandOutlineButton(
                    text = "Refresh",
                    onClick = { viewModel.refreshGatewayStats() },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
                BrandOutlineButton(
                    text = "Turn off",
                    onClick = { viewModel.stopGateway() },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            state.gatewayError?.let { Text(it, color = colors.accentRed, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Route everything", color = colors.textPrimary, fontSize = 13.sp)
                    Text(
                        "All apps use the computer's connection.",
                        color = colors.textSecondary, fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = state.gatewayFullTunnel,
                    onCheckedChange = { full ->
                        viewModel.setGatewayMode(full)
                    },
                )
            }
            Button(
                onClick = {
                    val intent = viewModel.gatewayConsentIntent()
                    if (intent != null) consent.launch(intent)
                    else viewModel.startGateway(desktop, state.gatewayFullTunnel)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.killSwitchActive,
            ) { Text("START GATEWAY") }
            if (state.killSwitchActive) {
                Text(
                    "Remote features are off because the kill switch is engaged.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
            }
            state.gatewayPhonePublicKey?.let { key ->
                SectionHeading("This phone's key", subtitle = "Paste it into the computer's gateway config")
                Text(key, color = colors.textPrimary, fontSize = 11.sp)
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun RemoteInternetSharingTab(
    state: RemoteControlViewModel.UiState,
    viewModel: RemoteControlViewModel,
) {
    val colors = AppTheme.colors
    val desktop = state.pairedDesktops.firstOrNull()

    PlatformCard(
        title = "Share this computer's internet",
        subtitle = "Browse through the paired computer's connection",
        status = if (state.sharingRunning) "On" to PlatformStatus.HEALTHY else "Off" to PlatformStatus.IDLE,
    ) {
        Text(
            "While this is on, requests sent to the proxy address below leave through the paired " +
                "computer's network instead of this phone's. Useful when the phone has no data or " +
                "is on a restricted Wi-Fi network.",
            color = colors.textSecondary, fontSize = 12.sp,
        )

        if (desktop == null) {
            EmptyState(
                Icons.Filled.Computer,
                "No computer paired",
                "Connect a computer in the Gateway tab first, then come back here.",
            )
            return@PlatformCard
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Computer", color = colors.textSecondary, fontSize = 11.sp)
                Text(desktop.serverName, color = colors.textPrimary, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Link", color = colors.textSecondary, fontSize = 11.sp)
                Text(
                    "${desktop.host}:${desktop.port}",
                    color = colors.textPrimary, fontSize = 13.sp,
                )
            }
        }

        if (state.sharingRunning) {
            SectionHeading("Proxy address", subtitle = "Enter this in your browser or Wi-Fi proxy settings")
            Text(
                state.sharingProxyAddress ?: "starting…",
                color = colors.electricBlue, fontSize = 15.sp,
            )
            Text(
                "Through ${state.sharingDesktopName ?: desktop.serverName} · " +
                    "${state.sharingRequests} request(s) · ${state.sharingBytes / 1024} KB",
                color = colors.textSecondary, fontSize = 11.sp,
            )
            Text(
                "If the computer goes offline, requests fail until it is back. Nothing falls back to " +
                    "this phone's own connection, so you always know which path is in use.",
                color = colors.textSecondary, fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandOutlineButton(
                    text = "Refresh",
                    onClick = { viewModel.refreshSharingStats() },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
                BrandOutlineButton(
                    text = "Stop sharing",
                    onClick = { viewModel.stopInternetSharing() },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            state.sharingError?.let {
                Text(it, color = colors.accentRed, fontSize = 12.sp)
            }
            Button(
                onClick = { viewModel.startInternetSharing(desktop) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.killSwitchActive,
            ) { Text("START SHARING") }
            if (state.killSwitchActive) {
                Text(
                    "Remote features are off because the kill switch is engaged.",
                    color = colors.textSecondary, fontSize = 11.sp,
                )
            }
        }
    }

    GatewayCard(state, viewModel, desktop)

    PlatformCard(
        title = "On the computer",
        subtitle = "The agent must be running with sharing enabled",
    ) {
        Text(
            "Start the desktop agent normally. It serves a SOCKS5 proxy on port " +
                "${com.opendroid.ai.remote.socks.Socks5.DEFAULT_PORT} and only accepts devices " +
                "paired with it. Add --no-share-internet to turn it off, or --share-port to change " +
                "the port.",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        Text(
            "The proxy is reachable only where the computer can be reached. On the same Wi-Fi that " +
                "is the local address; from outside, the computer needs to be reachable itself, " +
                "for example through a VPN or a port forward you control.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }
}
