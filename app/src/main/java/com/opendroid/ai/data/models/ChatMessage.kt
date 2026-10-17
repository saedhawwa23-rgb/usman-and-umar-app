package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val modelBadge: String? = null,
    val imageBase64: String? = null,
    val contactPickerData: String? = null,
    /** Ephemeral provider protocol fields; never stored in the visible conversation table. */
    @kotlinx.serialization.Transient val providerRole: String? = null,
    @kotlinx.serialization.Transient val toolCallId: String? = null,
    @kotlinx.serialization.Transient val toolCallsJson: String? = null
) {
    enum class Sender {
        USER, AGENT
    }
}
