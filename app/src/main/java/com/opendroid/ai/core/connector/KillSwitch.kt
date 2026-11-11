package com.opendroid.ai.core.connector

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide, thread-safe gate callers can check before device actions. */
object KillSwitch {
    private val mutableEngaged = MutableStateFlow(false)

    /** Observable state for settings and other UI consumers. */
    val state: StateFlow<Boolean> = mutableEngaged.asStateFlow()

    @get:Synchronized
    val isEngaged: Boolean
        get() = mutableEngaged.value

    @Synchronized
    fun engage() {
        mutableEngaged.value = true
    }

    @Synchronized
    fun release() {
        mutableEngaged.value = false
    }
}
