package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.connector.CloudflareConnectorManager
import com.opendroid.ai.core.connector.CloudflareServiceState
import com.opendroid.ai.core.connector.UniversalAgentEvent
import com.opendroid.ai.core.connector.UnifiedAgentLoop
import com.opendroid.ai.core.connector.ConnectorApprovalCoordinator
import com.opendroid.ai.core.connector.PendingConnectorApproval
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

@HiltViewModel
class UniversalAgentViewModel @Inject constructor(
    private val agent: UnifiedAgentLoop,
    private val cloudflare: CloudflareConnectorManager,
    private val approvals: ConnectorApprovalCoordinator,
) : ViewModel() {
    private val mutableEvents = MutableStateFlow<List<UniversalAgentEvent>>(emptyList())
    val events: StateFlow<List<UniversalAgentEvent>> = mutableEvents.asStateFlow()
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()
    val pendingApproval: StateFlow<PendingConnectorApproval?> = approvals.pending
    private var runJob: Job? = null
    private var stopRequested: AtomicBoolean? = null
    val cloudflareState: StateFlow<CloudflareServiceState> = cloudflare.state

    init { viewModelScope.launch { cloudflare.refresh() } }

    fun run(goal: String) {
        if (goal.isBlank() || mutableRunning.value) return
        val stopToken = AtomicBoolean(false)
        stopRequested = stopToken
        runJob = viewModelScope.launch {
            mutableRunning.value = true
            mutableEvents.value = emptyList()
            try {
                mutableEvents.value = agent.run(goal, maxSteps = UnifiedAgentLoop.MAX_STEPS, stopRequested = stopToken).events
            } catch (cancelled: CancellationException) {
                mutableEvents.value = mutableEvents.value + UniversalAgentEvent("cancelled", "Stopped by user; any in-flight request was cancelled where supported.")
                throw cancelled
            } finally {
                mutableRunning.value = false
                runJob = null
                stopRequested = null
            }
        }
    }

    fun stop() { stopRequested?.set(true) }
    fun approvePendingCall() { approvals.decide(true) }
    fun denyPendingCall() { approvals.decide(false) }

    fun connectCloudflare(token: String) { viewModelScope.launch { cloudflare.connect(token) } }
    fun disconnectCloudflare() { cloudflare.disconnect() }
}
