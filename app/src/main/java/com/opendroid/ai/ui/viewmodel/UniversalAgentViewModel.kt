package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.connector.CloudflareConnectorManager
import com.opendroid.ai.core.connector.CloudflareServiceState
import com.opendroid.ai.core.connector.UniversalAgentEvent
import com.opendroid.ai.core.connector.CloudToolAgent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UniversalAgentViewModel @Inject constructor(
    private val agent: CloudToolAgent,
    private val cloudflare: CloudflareConnectorManager
) : ViewModel() {
    private val mutableEvents = MutableStateFlow<List<UniversalAgentEvent>>(emptyList())
    val events: StateFlow<List<UniversalAgentEvent>> = mutableEvents.asStateFlow()
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()
    val cloudflareState: StateFlow<CloudflareServiceState> = cloudflare.state

    init { viewModelScope.launch { cloudflare.refresh() } }

    fun run(goal: String) {
        if (goal.isBlank() || mutableRunning.value) return
        viewModelScope.launch {
            mutableRunning.value = true
            mutableEvents.value = emptyList()
            mutableEvents.value = agent.run(goal).events
            mutableRunning.value = false
        }
    }

    fun connectCloudflare(token: String) { viewModelScope.launch { cloudflare.connect(token) } }
    fun disconnectCloudflare() { cloudflare.disconnect() }
}
