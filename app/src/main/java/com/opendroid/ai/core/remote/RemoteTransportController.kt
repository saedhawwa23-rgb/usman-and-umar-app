package com.opendroid.ai.core.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on/off state of the LAN listener so the UI can start and stop it and observe whether
 * it is reachable. The listener is off until the owner turns it on; this controller never starts
 * it on its own.
 */
@Singleton
class RemoteTransportController @Inject constructor(
    private val server: LanRemoteControlServer,
) {

    data class State(
        val running: Boolean = false,
        val port: Int? = null,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(State(running = server.isRunning))
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Bind the listener. Returns a plain message on success or failure. */
    fun start(): String = runCatching { server.start() }.fold(
        onSuccess = { port ->
            mutableState.value = State(running = true, port = port)
            "Listening for paired devices on port $port. Keep this on only on a trusted network."
        },
        onFailure = { error ->
            mutableState.value = State(running = false, error = error.message)
            "Could not start listening: ${error.message ?: "unknown error"}"
        },
    )

    fun stop() {
        runCatching { server.stop() }
        mutableState.value = State(running = false)
    }

    /** Called after the kill switch engages: the listener must not keep accepting. */
    fun stopForKillSwitch() {
        stop()
    }
}
