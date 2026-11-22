package com.opendroid.ai.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.remote.DesktopSessionState
import com.opendroid.ai.core.remote.LanRemoteClient
import com.opendroid.ai.core.remote.PairedDesktop
import com.opendroid.ai.core.remote.PairedDesktopStore
import com.opendroid.ai.core.remote.RemoteApprovalGate
import com.opendroid.ai.core.remote.RemoteDesktopClient
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import com.opendroid.ai.core.remote.RemoteRisk
import com.opendroid.ai.core.remote.RemoteSession
import com.opendroid.ai.core.remote.RemoteTlsMode
import com.opendroid.ai.core.remote.gateway.ExitIpVerifier
import com.opendroid.ai.core.remote.RemoteTransportController
import com.opendroid.ai.remote.protocol.AppEntry
import com.opendroid.ai.remote.protocol.AppLaunchResult
import com.opendroid.ai.remote.protocol.AppList
import com.opendroid.ai.remote.protocol.ClipboardSet
import com.opendroid.ai.remote.protocol.ClipboardValue
import com.opendroid.ai.remote.protocol.FileEntry
import com.opendroid.ai.remote.protocol.FileList
import com.opendroid.ai.remote.protocol.InputEvent
import com.opendroid.ai.remote.protocol.ProtocolError
import com.opendroid.ai.remote.protocol.RemoteMessage
import com.opendroid.ai.remote.protocol.ScreenFrameCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Remote Control screen. It holds only presentation state and forwards every
 * decision to [RemoteControlCoordinator]; no cryptography or key handling happens here.
 */
@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val coordinator: RemoteControlCoordinator,
    private val transport: RemoteTransportController,
    private val approvalGate: RemoteApprovalGate,
    private val client: LanRemoteClient,
    private val desktopClient: RemoteDesktopClient,
    private val desktopStore: PairedDesktopStore,
    private val internetSharing: com.opendroid.ai.core.remote.InternetSharingController,
    private val gateway: com.opendroid.ai.core.remote.gateway.InternetGatewayController,
    private val gatewayKeys: com.opendroid.ai.core.remote.gateway.GatewayKeyStore,
    private val exitIpVerifier: com.opendroid.ai.core.remote.gateway.ExitIpVerifier,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    /** What the screen shows. [pairingCode] is non-null only while a code is live. */
    data class UiState(
        val pairingCode: String? = null,
        val pairingDeviceId: String? = null,
        val pairingExpiresAtMs: Long = 0L,
        val pairedDevices: List<String> = emptyList(),
        val sessions: List<RemoteSession> = emptyList(),
        val killSwitchActive: Boolean = false,
        val listening: Boolean = false,
        val listenPort: Int? = null,
        val pendingApproval: RemoteApprovalGate.Pending? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
        // Desktop companion agent
        val pairedDesktops: List<PairedDesktop> = emptyList(),
        val desktopConnected: Boolean = false,
        val desktopName: String? = null,
        val desktopStreaming: Boolean = false,
        val lastScreenFrame: ScreenFrameCodec.ScreenFrame? = null,
        val desktopApplications: List<AppEntry> = emptyList(),
        /** The last clipboard text the desktop reported; null until asked for. */
        val desktopClipboard: String? = null,
        /** True while the phone's own clipboard is pushed to the desktop automatically. */
        val clipboardSyncEnabled: Boolean = false,
        /** Bytes received so far in the current download, for the progress line. */
        val transferProgress: Long = 0L,
        // Secure file transfer (desktop agent)
        val remotePath: String = ".",
        val remoteEntries: List<FileEntry> = emptyList(),
        // Remote terminal (executed as an authorized remote action on this phone)
        val terminalOutput: String? = null,
        // Internet sharing: this phone's traffic leaving through a paired desktop
        val sharingRunning: Boolean = false,
        /** Loopback HTTP proxy the owner points a browser at, e.g. 127.0.0.1:41234. */
        val sharingProxyAddress: String? = null,
        val sharingDesktopName: String? = null,
        val sharingRequests: Long = 0,
        val sharingBytes: Long = 0,
        val sharingError: String? = null,
        // Internet gateway: full-tunnel VPN so every app leaves through the paired desktop
        val gatewayRunning: Boolean = false,
        val gatewayFullTunnel: Boolean = true,
        val gatewayEndpoint: String? = null,
        val gatewayDesktopName: String? = null,
        val gatewayBytesIn: Long = 0,
        val gatewayBytesOut: Long = 0,
        val gatewayConnectedSinceMs: Long = 0,
        val gatewayLastHandshakeMs: Long = 0,
        val gatewayError: String? = null,
        val gatewayNeedsPermission: Boolean = false,
        /** This phone's public key, shown so it can be pasted into the desktop's config. */
        val gatewayPhonePublicKey: String? = null,
        /** Exit IP measured with the tunnel down, or null before a check has run. */
        val gatewayExitIpBefore: String? = null,
        /** Exit IP measured with the tunnel up; differs from [gatewayExitIpBefore] when routing works. */
        val gatewayExitIpAfter: String? = null,
        val gatewayExitIpError: String? = null,
        val gatewayExitIpChecking: Boolean = false,
    )

    private val mutableState = MutableStateFlow(
        UiState(
            pairedDevices = coordinator.pairedKeyIds(),
            sessions = coordinator.sessions(),
            killSwitchActive = coordinator.isKillSwitchActive(),
            listening = transport.state.value.running,
            listenPort = transport.state.value.port,
            pairedDesktops = desktopStore.load(),
            sharingRunning = internetSharing.state.value.running,
            sharingProxyAddress = internetSharing.state.value.proxyAddress,
            sharingDesktopName = internetSharing.state.value.desktopName,
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        // Files land in the app's own external files dir, which needs no storage permission and is
        // removed with the app. Nothing is written anywhere else.
        desktopClient.onDownloadComplete = { path, bytes ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val name = path.substringAfterLast('/').ifBlank { "download.bin" }
                val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "remote-downloads")
                dir.mkdirs()
                val out = java.io.File(dir, name)
                runCatching { out.writeBytes(bytes) }.fold(
                    onSuccess = {
                        mutableState.value = mutableState.value.copy(
                            message = "Saved ${bytes.size / 1024} KB to ${out.name}.",
                            messageIsError = false,
                        )
                    },
                    onFailure = {
                        mutableState.value = mutableState.value.copy(
                            message = "Could not save ${out.name}: ${it.message ?: "unknown error"}",
                            messageIsError = true,
                        )
                    },
                )
            }
        }
        desktopClient.onDownloadProgress = { _, received ->
            mutableState.value = mutableState.value.copy(transferProgress = received)
        }
        viewModelScope.launch {
            transport.state.collect { s ->
                mutableState.value = mutableState.value.copy(listening = s.running, listenPort = s.port)
            }
        }
        viewModelScope.launch {
            approvalGate.current.collect { pending ->
                mutableState.value = mutableState.value.copy(pendingApproval = pending)
            }
        }
        // Evaluate session policy once a second so an inactivity or max-duration end is shown with
        // its reason, and so a reconnecting session is visible while it recovers.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                coordinator.tickSessions()
                mutableState.value = mutableState.value.copy(sessions = coordinator.sessions())
            }
        }
    }

    private val desktopConnected: Boolean get() = mutableState.value.desktopConnected

    private val pairedDesktops: List<PairedDesktop> get() = mutableState.value.pairedDesktops

    /** Turn the LAN listener on so a paired device can reach this phone. */
    fun startListening() {
        if (mutableState.value.killSwitchActive) {
            setMessage("Remote control is disabled by the kill switch.", isError = true)
            return
        }
        val result = transport.start()
        setMessage(result, isError = !transport.state.value.running)
    }

    fun stopListening() {
        transport.stop()
        setMessage("Stopped listening. Paired devices can no longer reach this phone.", isError = false)
    }

    /**
     * Turn on internet sharing through [desktop]: this phone's browser traffic then leaves via that
     * machine's connection. Refused while the kill switch is engaged, like every other remote path.
     */
    fun startInternetSharing(desktop: PairedDesktop) {
        if (mutableState.value.killSwitchActive) {
            setMessage("Internet sharing is disabled by the kill switch.", isError = true)
            return
        }
        val result = internetSharing.start(desktop)
        val state = internetSharing.state.value
        mutableState.value = mutableState.value.copy(
            sharingRunning = state.running,
            sharingProxyAddress = state.proxyAddress,
            sharingDesktopName = state.desktopName,
            sharingRequests = 0,
            sharingBytes = 0,
            sharingError = state.error,
        )
        setMessage(result, isError = !state.running)
    }

    fun stopInternetSharing() {
        internetSharing.stop()
        mutableState.value = mutableState.value.copy(
            sharingRunning = false,
            sharingProxyAddress = null,
            sharingDesktopName = null,
            sharingRequests = 0,
            sharingBytes = 0,
            sharingError = null,
        )
        setMessage("Internet sharing is off. Traffic uses this phone's own connection again.", isError = false)
    }

    /** Pull the live counters while sharing is running, for the status card. */
    fun refreshSharingStats() {
        if (!mutableState.value.sharingRunning) return
        internetSharing.refreshStats()
        val state = internetSharing.state.value
        mutableState.value = mutableState.value.copy(
            sharingRequests = state.requestsServed,
            sharingBytes = state.bytesRelayed,
        )
    }

    /**
     * Start the full-tunnel gateway through [desktop].
     *
     * Refused while the kill switch is engaged, and refused with a specific reason when the phone
     * still has no VPN consent or the desktop has not published its gateway key. A half-started
     * tunnel would black-hole the phone's traffic, so every precondition is checked first.
     */
    fun startGateway(desktop: PairedDesktop, fullTunnel: Boolean = true) {
        if (mutableState.value.killSwitchActive) {
            setMessage("Internet sharing is disabled by the kill switch.", isError = true)
            return
        }
        gateway.setMode(
            if (fullTunnel) com.opendroid.ai.core.remote.gateway.InternetGatewayController.Mode.FULL
            else com.opendroid.ai.core.remote.gateway.InternetGatewayController.Mode.SPLIT,
        )

        // A peer minted for a different computer (or for a key the desktop has since rotated)
        // would never handshake, and WireGuard reports that only as silence. Rebuild it instead.
        val expectedEndpoint =
            "${desktop.host}:${desktop.gatewayPort ?: com.opendroid.ai.remote.gateway.GatewayConfig.DEFAULT_LISTEN_PORT}"
        val stored = gatewayKeys.load()?.takeIf {
            it.desktopPublicKey == desktop.gatewayPublicKey && it.endpoint == expectedEndpoint
        }
        val peer = when {
            stored != null -> stored
            desktop.gatewayPublicKey.isNullOrBlank() -> {
                setMessage(
                    "This computer has not published a gateway key yet. Update the desktop agent " +
                        "and re-pair, then try again.",
                    isError = true,
                )
                return
            }
            else -> {
                // First use on this phone: mint the phone key and keep the desktop's public half.
                val generated = com.opendroid.ai.core.remote.gateway.GatewayKeys.generate()
                val created = com.opendroid.ai.remote.gateway.GatewayPeer(
                    privateKey = generated.privateKeyBase64,
                    desktopPublicKey = desktop.gatewayPublicKey!!,
                    endpoint = expectedEndpoint,
                )
                gatewayKeys.save(created)
                created
            }
        }

        // A baseline is only meaningful while the tunnel is down, so take it before starting.
        if (mutableState.value.gatewayExitIpBefore == null) captureGatewayExitBaseline()

        val started = gateway.start(peer, desktop.serverName)
        val gs = gateway.state.value
        mutableState.value = mutableState.value.copy(
            gatewayRunning = gs.running,
            gatewayFullTunnel = fullTunnel,
            gatewayEndpoint = gs.endpoint,
            gatewayDesktopName = gs.desktopName,
            gatewayError = gs.error,
            gatewayNeedsPermission = gs.needsPermission,
            gatewayPhonePublicKey = runCatching {
                com.opendroid.ai.core.remote.gateway.GatewayKeys.publicKeyOf(peer.privateKey)
            }.getOrNull(),
        )
        if (started) {
            setMessage("Internet gateway is starting through ${desktop.serverName}.", isError = false)
        } else {
            setMessage(gs.error ?: "The gateway could not start.", isError = true)
        }
    }

    fun stopGateway() {
        gateway.stop()
        mutableState.value = mutableState.value.copy(
            gatewayRunning = false,
            gatewayBytesIn = 0,
            gatewayBytesOut = 0,
            gatewayConnectedSinceMs = 0,
            gatewayLastHandshakeMs = 0,
            gatewayError = null,
            gatewayNeedsPermission = false,
        )
        setMessage("Gateway is off. All apps use this phone's own connection again.", isError = false)
    }

    /**
     * Record the exit IP with the tunnel down, so a later reading can be compared against it.
     *
     * Without a baseline a changed address proves nothing.
     */
    fun captureGatewayExitBaseline() {
        if (mutableState.value.gatewayExitIpChecking) return
        mutableState.value = mutableState.value.copy(
            gatewayExitIpChecking = true,
            gatewayExitIpError = null,
            gatewayExitIpAfter = null,
        )
        viewModelScope.launch {
            when (val result = exitIpVerifier.fetch()) {
                is ExitIpVerifier.Result.Success -> mutableState.value = mutableState.value.copy(
                    gatewayExitIpBefore = result.ip,
                    gatewayExitIpChecking = false,
                )
                is ExitIpVerifier.Result.Failure -> mutableState.value = mutableState.value.copy(
                    gatewayExitIpError = result.reason,
                    gatewayExitIpChecking = false,
                )
            }
        }
    }

    /**
     * Re-read the exit IP with the tunnel up and compare it with the baseline.
     *
     * A tunnel that is up and handshaking still routes nothing if the desktop does not forward, so
     * this is the only check that answers "does my traffic actually leave through the computer".
     */
    fun verifyGatewayExit() {
        if (mutableState.value.gatewayExitIpChecking) return
        mutableState.value = mutableState.value.copy(gatewayExitIpChecking = true, gatewayExitIpError = null)
        viewModelScope.launch {
            when (val result = exitIpVerifier.fetch()) {
                is ExitIpVerifier.Result.Success -> {
                    val before = mutableState.value.gatewayExitIpBefore
                    val problem = when {
                        before == null ->
                            "No baseline was recorded before the tunnel started, so the address cannot " +
                                "be compared. Stop the gateway, record the baseline, then try again."
                        !ExitIpVerifier.changed(before, result.ip) ->
                            "The exit address is unchanged, so traffic is still leaving through this " +
                                "phone. The tunnel is up but the computer is not forwarding it."
                        else -> null
                    }
                    mutableState.value = mutableState.value.copy(
                        gatewayExitIpAfter = result.ip,
                        gatewayExitIpChecking = false,
                        gatewayExitIpError = problem,
                    )
                }
                is ExitIpVerifier.Result.Failure -> mutableState.value = mutableState.value.copy(
                    gatewayExitIpError = result.reason,
                    gatewayExitIpChecking = false,
                )
            }
        }
    }

    /** Pull live tunnel counters for the status card. */
    fun refreshGatewayStats() {
        if (!mutableState.value.gatewayRunning) return
        val gs = gateway.state.value
        mutableState.value = mutableState.value.copy(
            gatewayRunning = gs.running,
            gatewayBytesIn = gs.bytesIn,
            gatewayBytesOut = gs.bytesOut,
            gatewayConnectedSinceMs = gs.connectedSinceMs,
            gatewayLastHandshakeMs = gs.lastHandshakeMs,
            gatewayError = gs.error,
        )
    }

    /** The consent dialog to show, or null when the OS has already granted VPN permission. */
    fun gatewayConsentIntent(): android.content.Intent? = gateway.consentIntent()

    /** Choose whether the tunnel carries everything or only the selected routes. */
    fun setGatewayMode(fullTunnel: Boolean) {
        mutableState.value = mutableState.value.copy(gatewayFullTunnel = fullTunnel)
        gateway.setMode(
            if (fullTunnel) com.opendroid.ai.core.remote.gateway.InternetGatewayController.Mode.FULL
            else com.opendroid.ai.core.remote.gateway.InternetGatewayController.Mode.SPLIT,
        )
    }

    fun clearGatewayError() {
        gateway.clearError()
        mutableState.value = mutableState.value.copy(gatewayError = null, gatewayNeedsPermission = false)
    }

    /** The owner's answer to an inbound remote action. */
    fun approvePending() = approvalGate.decide(true)

    fun denyPending() = approvalGate.decide(false)

    /** Show the transfer code for a paired device so the owner can move it to the controller. */
    fun exportTransferCode(keyId: String) {
        val code = coordinator.exportTransferCode(keyId)
        setMessage(code ?: "That device is not paired.", isError = code == null)
    }

    /** Import a transfer code from the other device so this phone can control it. */
    fun importTransferCode(blob: String) {
        when (val result = coordinator.importTransferCode("key-" + UUID.randomUUID().toString().take(8), blob)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    pairedDevices = coordinator.pairedKeyIds(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    /**
     * Send one signed action to a paired device. Requires a paired key and the device's address.
     * The action is signed here; nothing leaves unsigned.
     */
    fun sendAction(host: String, toolName: String, argumentsJson: String) {
        val keyId = mutableState.value.pairedDevices.firstOrNull()
        if (keyId == null) {
            setMessage("Pair a device first; there is no key to sign with.", isError = true)
            return
        }
        viewModelScope.launch {
            val signed = runCatching {
                coordinator.sign(keyId, toolName, argumentsJson, RemoteRisk.HIGH)
            }.getOrElse {
                setMessage("Could not sign the request: ${it.message ?: "unknown error"}", isError = true)
                return@launch
            }
            val reply = client.send(host, request = signed)
            reply.fold(
                onSuccess = { setMessage("${it.toolName}: ${it.detail}", isError = !it.executed) },
                onFailure = { setMessage("Send failed: ${it.message ?: "unknown error"}", isError = true) },
            )
        }
    }

    /** Start a pairing session for this device. The code is shown to the owner to read out. */
    fun startPairing() {
        if (mutableState.value.killSwitchActive) {
            setMessage("Remote control is disabled by the kill switch.", isError = true)
            return
        }
        val start = coordinator.startPairing(
            deviceId = "this-device-" + UUID.randomUUID().toString().take(8),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "This device" },
        )
        mutableState.value = mutableState.value.copy(
            pairingCode = start.code,
            pairingDeviceId = start.deviceId,
            pairingExpiresAtMs = start.expiresAtMs,
            message = "Enter this code on the controlling device.",
            messageIsError = false,
        )
    }

    /** Complete pairing with the code shown on the controlling device. */
    fun completePairing(code: String) {
        when (val result = coordinator.completePairing(code, keyId = "key-" + UUID.randomUUID().toString().take(8))) {
            is RemoteControlCoordinator.StepResult.Success -> {
                mutableState.value = mutableState.value.copy(
                    pairingCode = null,
                    pairingDeviceId = null,
                    pairingExpiresAtMs = 0L,
                    pairedDevices = coordinator.pairedKeyIds(),
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            }
            is RemoteControlCoordinator.StepResult.Rejected ->
                setMessage(result.reason, isError = true)
        }
    }

    fun unpair(keyId: String) {
        when (val result = coordinator.unpair(keyId)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    pairedDevices = coordinator.pairedKeyIds(),
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    /** Disconnect a live session immediately; the reason is shown to the owner. */
    fun disconnectSession(deviceId: String) {
        when (val result = coordinator.disconnectSession(deviceId)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    fun engageKillSwitch() {
        val result = coordinator.engageKillSwitch()
        // Sharing must not outlive the kill switch: an open tunnel would keep carrying traffic
        // after the owner has asked every remote path to stop.
        internetSharing.stopForKillSwitch()
        // The VPN is the widest remote path of all: it carries every app's traffic, so it goes down
        // with the kill switch too.
        gateway.stop()
        mutableState.value = mutableState.value.copy(
            pairingCode = null,
            pairingDeviceId = null,
            pairingExpiresAtMs = 0L,
            killSwitchActive = true,
            sharingRunning = false,
            gatewayRunning = false,
            gatewayBytesIn = 0,
            gatewayBytesOut = 0,
            gatewayConnectedSinceMs = 0,
            sharingProxyAddress = null,
            sharingDesktopName = null,
            sessions = coordinator.sessions(),
            message = (result as? RemoteControlCoordinator.StepResult.Success)?.message,
            messageIsError = false,
        )
    }

    fun secondsRemaining(): Long =
        mutableState.value.pairingExpiresAtMs.takeIf { it > 0 }?.let(coordinator::secondsRemaining) ?: 0L

    // ---- Desktop companion agent ----

    /**
     * Pair with a desktop agent running on a computer. The code is the one the agent printed when it
     * started; nothing is stored unless the agent accepts it.
     */
    fun pairWithDesktop(host: String, port: Int, code: String, certificateFingerprint: String? = null) {
        if (desktopConnected) {
            setMessage("Disconnect the current desktop before pairing with another.", isError = true)
            return
        }
        // Pinning needs the fingerprint the desktop printed. Without one the user has chosen a
        // trusted local network, so plain TCP is used and the message says so.
        desktopClient.tlsMode = certificateFingerprint
            ?.takeIf { it.isNotBlank() }
            ?.let { RemoteTlsMode.Pinned(it) }
            ?: RemoteTlsMode.Plain
        viewModelScope.launch {
            desktopClient.connect(
                host = host.trim(),
                port = port,
                pairingCode = code.trim().uppercase(),
                onState = ::onDesktopState,
                onScreenFrame = { frame -> mutableState.value = mutableState.value.copy(lastScreenFrame = frame) },
                onMessage = ::onDesktopMessage,
            )
        }
    }

    /** Connect to a desktop that has already been paired. */
    fun connectToDesktop(host: String, port: Int) {
        if (desktopConnected) return
        val known = desktopStore.load().firstOrNull { it.host == host.trim() && it.port == port }
        desktopClient.tlsMode = known?.certificateFingerprint
            ?.let { RemoteTlsMode.Pinned(it) }
            ?: RemoteTlsMode.Plain
        viewModelScope.launch {
            desktopClient.connect(
                host = host.trim(),
                port = port,
                pairingCode = null,
                onState = ::onDesktopState,
                onScreenFrame = { frame -> mutableState.value = mutableState.value.copy(lastScreenFrame = frame) },
                onMessage = ::onDesktopMessage,
            )
        }
    }

    fun disconnectDesktop() {
        desktopClient.disconnect("disconnected from the phone")
        mutableState.value = mutableState.value.copy(
            desktopStreaming = false,
            message = "Disconnected from the desktop.",
            messageIsError = false,
        )
    }

    fun startDesktopStream(width: Int, quality: Int) {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before viewing its screen.", isError = true)
            return
        }
        desktopClient.requestScreen(width, quality)
        mutableState.value = mutableState.value.copy(desktopStreaming = true)
    }

    fun stopDesktopStream() {
        desktopClient.stopScreen()
        mutableState.value = mutableState.value.copy(desktopStreaming = false, lastScreenFrame = null)
    }

    fun sendDesktopInput(event: InputEvent) {
        if (!desktopConnected) return
        desktopClient.sendInput(event)
    }

    // ---- Clipboard ----

    /** Ask the desktop for its current clipboard text. */
    fun requestDesktopClipboard() {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before syncing the clipboard.", isError = true)
            return
        }
        desktopClient.requestClipboard()
    }

    /**
     * Send text to the desktop clipboard. [text] usually comes from this phone's clipboard, which
     * the screen reads; the view model never touches the Android clipboard itself so it stays
     * testable off-device.
     */
    fun sendToDesktopClipboard(text: String) {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before syncing the clipboard.", isError = true)
            return
        }
        if (text.isBlank()) {
            setMessage("There is no text to send.", isError = true)
            return
        }
        val sent = desktopClient.setClipboard(text)
        setMessage(
            if (sent) "Sent ${text.length} characters to the desktop clipboard."
            else "That text is too large for the clipboard (limit ${ClipboardSet.CLIPBOARD_TEXT_LIMIT} characters).",
            isError = !sent,
        )
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(
            clipboardSyncEnabled = enabled,
            message = if (enabled) "Clipboard sync is on: text you copy here is sent to the desktop."
            else "Clipboard sync is off.",
            messageIsError = false,
        )
    }

    fun listDesktopApplications() {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before listing its applications.", isError = true)
            return
        }
        desktopClient.listApplications()
    }

    fun launchDesktopApplication(id: String) {
        if (!desktopConnected) return
        desktopClient.launchApplication(id)
    }

    /** Forget every paired desktop on this phone. The desktops are told as well. */
    fun forgetDesktops() {
        val count = pairedDesktops.size
        desktopClient.revoke()
        mutableState.value = mutableState.value.copy(
            pairedDesktops = emptyList(),
            desktopConnected = false,
            desktopStreaming = false,
            lastScreenFrame = null,
            desktopApplications = emptyList(),
            message = if (count == 0) "No desktops were paired." else "Forgot $count paired desktop(s).",
            messageIsError = false,
        )
    }

    /** Browse a folder on the paired computer. Empty path lists the agent's shared folder root. */
    fun listRemoteFiles(path: String) {
        if (!desktopConnected) {
            setMessage("Connect to the computer first.", isError = true)
            return
        }
        val target = path.trim().ifBlank { "." }
        desktopClient.listFiles(target)
        mutableState.value = mutableState.value.copy(remotePath = target)
    }

    /**
     * Download a file from the paired computer into this phone's app storage. The bytes travel over
     * the already-authenticated desktop session; nothing is uploaded to a third party.
     */
    fun downloadRemoteFile(path: String) {
        if (!desktopConnected) {
            setMessage("Connect to the computer first.", isError = true)
            return
        }
        desktopClient.downloadFile(path)
        setMessage("Requested '$path' from the computer.", isError = false)
    }

    /**
     * Upload a file the user picked to the desktop's shared folder.
     *
     * The bytes are read on an IO dispatcher and streamed in chunks; the desktop is the authority on
     * where the file may be written, so a path outside its shared folder is refused by the agent.
     */
    fun uploadToDesktop(file: java.io.File, remotePath: String? = null) {
        if (!desktopConnected) {
            setMessage("Connect to the desktop before uploading a file.", isError = true)
            return
        }
        if (!file.isFile) {
            setMessage("That file could not be read.", isError = true)
            return
        }
        viewModelScope.launch {
            val bytes = runCatching { file.readBytes() }.getOrElse {
                setMessage("Could not read ${file.name}: ${it.message ?: "unknown error"}", isError = true)
                return@launch
            }
            val target = remotePath ?: "${mutableState.value.remotePath.trimEnd('.', '/')}/${file.name}".replace("//", "/")
            setMessage("Uploading ${file.name} (${bytes.size / 1024} KB)…", isError = false)
            desktopClient.uploadFile(target, bytes).fold(
                onSuccess = { setMessage("Uploaded ${file.name} to $target.", isError = false) },
                onFailure = { setMessage("Upload failed: ${it.message ?: "unknown error"}", isError = true) },
            )
        }
    }

    /**
     * Run an authorized remote terminal command on THIS phone, sent from a paired controller.
     * It uses the same signed-action path as every other remote request: the owner is asked to
     * approve, the kill switch and approval gate apply, and nothing runs before approval.
     */
    fun runRemoteTerminal(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            setMessage("Enter a command to send.", isError = true)
            return
        }
        val keyId = mutableState.value.pairedDevices.firstOrNull()
        if (keyId == null) {
            setMessage("Pair a device first; there is no key to sign with.", isError = true)
            return
        }
        viewModelScope.launch {
            val signed = runCatching {
                coordinator.sign(keyId, "shell", """{"command":${jsonString(trimmed)}}""", RemoteRisk.HIGH)
            }.getOrElse {
                setMessage("Could not sign the request: ${it.message ?: "unknown error"}", isError = true)
                return@launch
            }
            val reply = client.send(host = "127.0.0.1", request = signed)
            reply.fold(
                onSuccess = { mutableState.value = mutableState.value.copy(terminalOutput = it.detail) },
                onFailure = { setMessage("Remote terminal failed: ${it.message ?: "unknown error"}", isError = true) },
            )
        }
    }

    private fun jsonString(value: String): String =
        com.google.gson.JsonPrimitive(value).toString()

    private fun onDesktopState(newState: DesktopSessionState) {
        val current = mutableState.value
        mutableState.value = when (newState) {
            is DesktopSessionState.Connected -> current.copy(
                desktopConnected = true,
                desktopName = newState.serverName,
                message = "Connected to ${newState.serverName}.",
                messageIsError = false,
            )
            is DesktopSessionState.Connecting -> current.copy(
                message = "Connecting to the desktop…",
                messageIsError = false,
            )
            is DesktopSessionState.AwaitingPairing -> current.copy(
                message = newState.message,
                messageIsError = false,
            )
            is DesktopSessionState.Failed -> current.copy(
                desktopConnected = false,
                desktopStreaming = false,
                lastScreenFrame = null,
                pairedDesktops = desktopStore.load(),
                message = newState.reason,
                messageIsError = true,
            )
            is DesktopSessionState.Closed -> current.copy(
                desktopConnected = false,
                desktopStreaming = false,
                lastScreenFrame = null,
                message = newState.reason,
                messageIsError = false,
            )
            DesktopSessionState.Idle -> current
        }
    }

    private fun onDesktopMessage(message: RemoteMessage) {
        val current = mutableState.value
        when (message) {
            is AppList -> mutableState.value = current.copy(
                desktopApplications = message.entries,
                message = "${message.entries.size} application(s) on the desktop.",
                messageIsError = false,
            )
            is AppLaunchResult -> mutableState.value = current.copy(
                message = if (message.accepted) "The desktop started the application."
                else "The desktop refused to start it: ${message.reason ?: "no reason given"}",
                messageIsError = !message.accepted,
            )
            is FileList -> mutableState.value = current.copy(
                remotePath = message.path,
                remoteEntries = message.entries,
                message = if (message.entries.isEmpty()) "No files in ${message.path}."
                else "${message.entries.size} item(s) in ${message.path}.",
                messageIsError = false,
            )
            is ClipboardValue -> mutableState.value = current.copy(
                desktopClipboard = if (message.available) message.text else null,
                message = if (message.available) "Received the desktop clipboard (${message.text.length} characters)."
                else "The desktop clipboard holds no text.",
                messageIsError = false,
            )
            is ProtocolError -> mutableState.value = current.copy(
                message = "The desktop reported ${message.code}: ${message.message}",
                messageIsError = true,
            )
            else -> Unit
        }
    }

    private fun setMessage(text: String, isError: Boolean) {
        mutableState.value = mutableState.value.copy(message = text, messageIsError = isError)
    }
}
