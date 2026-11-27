package com.opendroid.ai.core.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Converts a [SignedRemoteRequest] to and from the JSON line that travels over the owner's
 * transport. The signature is computed over [SignedRemoteRequest.canonicalPayload], which is
 * rebuilt from the same fields on both sides, so the JSON only needs to survive a round trip.
 *
 * kotlinx.serialization is used rather than org.json so the codec is exercised by plain JVM unit
 * tests (org.json is a non-functional stub there). The tool arguments travel as a string and are
 * re-parsed by the tool, so a malformed blob is rejected there instead of corrupting the request.
 */
object RemoteRequestCodec {

    private val json = Json { ignoreUnknownKeys = false }

    @Serializable
    private data class Wire(
        val keyId: String,
        val deviceId: String,
        val toolName: String,
        val argumentsJson: String,
        val risk: String,
        val timestampMs: Long,
        val nonce: String,
        val signatureHex: String,
    )

    fun encode(request: SignedRemoteRequest): String = json.encodeToString(
        Wire(
            keyId = request.keyId,
            deviceId = request.deviceId,
            toolName = request.action.toolName,
            argumentsJson = request.action.argumentsJson,
            risk = request.action.risk.name,
            timestampMs = request.timestampMs,
            nonce = request.nonce,
            signatureHex = request.signatureHex,
        )
    )

    /** Returns null for anything that is not a complete, well-formed request. */
    fun decode(line: String): SignedRemoteRequest? = runCatching {
        val wire = json.decodeFromString<Wire>(line)
        SignedRemoteRequest(
            keyId = wire.keyId,
            deviceId = wire.deviceId,
            action = RemoteAction(
                toolName = wire.toolName,
                argumentsJson = wire.argumentsJson,
                risk = RemoteRisk.valueOf(wire.risk),
            ),
            timestampMs = wire.timestampMs,
            nonce = wire.nonce,
            signatureHex = wire.signatureHex,
        )
    }.getOrNull()
}

/**
 * The answer to a transported request, and the smallest thing the transport has to parse.
 * [detail] never carries a secret: it is a tool result or a plain rejection reason.
 */
data class RemoteTransportReply(
    val executed: Boolean,
    val toolName: String,
    val detail: String,
) {
    @Serializable
    private data class Wire(val executed: Boolean, val toolName: String, val detail: String)

    fun encode(): String = json.encodeToString(Wire(executed, toolName, detail))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(line: String): RemoteTransportReply? = runCatching {
            val wire = json.decodeFromString<Wire>(line)
            RemoteTransportReply(wire.executed, wire.toolName, wire.detail)
        }.getOrNull()
    }
}
