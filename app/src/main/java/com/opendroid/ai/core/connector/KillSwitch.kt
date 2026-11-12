package com.opendroid.ai.core.connector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide global automation gate. A fresh process starts disabled (fail-closed). */
object KillSwitch {
    private val mutableEnabled = MutableStateFlow(false)

    /** Observable state for the main screen and Settings. `true` means automation is enabled. */
    val state: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    val isEnabled: Boolean
        get() = mutableEnabled.value

    /** The only operation that permits local or cloud automation. */
    @Synchronized
    fun enable() {
        mutableEnabled.value = true
    }

    /** The only operation that blocks local and cloud automation. */
    @Synchronized
    fun disable() {
        mutableEnabled.value = false
    }
}
