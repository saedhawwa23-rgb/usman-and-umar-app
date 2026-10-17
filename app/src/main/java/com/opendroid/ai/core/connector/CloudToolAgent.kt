package com.opendroid.ai.core.connector

import com.google.gson.Gson
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.StreamChunk
import com.opendroid.ai.core.llm.ToolDefinition
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

class KeystoreConnectorCredentialResolver @Inject constructor(
    private val store: ProviderCredentialStore
) : ConnectorCredentialResolver {
    override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T {
        if (ref == null) return block(null)
        val credential = runCatching { ProviderCredentialId.ApiKey(ref.id) }.getOrNull()
            ?: return block(null)
        val value = when (val result = store.read(credential)) {
            is CredentialStoreResult.Success -> result.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> null
        }
        return block(value)
    }
}

data class UniversalAgentEvent(
    val type: String,
    val message: String,
    val invocationId: String? = null,
    val connectorId: String? = null,
    val toolName: String? = null
)

data class UniversalAgentResult(
    val completed: Boolean,
    val events: List<UniversalAgentEvent>,
    val lastOutput: String = ""
)

/** Cloud-provider agent using provider-native tool calls and explicit local connector policy. */
@Singleton
class CloudToolAgent @Inject constructor(
    private val providers: LLMProviderFactory,
    private val registry: UniversalToolRegistry,
    private val credentials: KeystoreConnectorCredentialResolver,
    private val executionLog: InMemoryConnectorExecutionLog
) {
    private val gson = Gson()

    init {
        // Read-only discovery is available without configuring an external service.
        registry.register(LocalRegistryConnector(registry))
    }

    suspend fun run(goal: String, maxSteps: Int = 12): UniversalAgentResult {
        require(goal.isNotBlank()) { "Goal must not be blank" }
        val events = mutableListOf<UniversalAgentEvent>()
        val provider = try {
            providers.getActiveProvider()
        } catch (failure: Exception) {
            return UniversalAgentResult(false, listOf(UniversalAgentEvent("error", failure.message ?: "No AI provider is selected.")))
        }
        events += UniversalAgentEvent("engine", "Using selected cloud provider: ${provider.name}")
        if (!runCatching { provider.isAvailable() }.getOrDefault(false)) {
            return UniversalAgentResult(
                false,
                events + UniversalAgentEvent("error", "${provider.name} is not configured. Add its API key or endpoint in Settings; no local model fallback is used.")
            )
        }

        val tools = registry.allTools()
        if (tools.isEmpty()) return UniversalAgentResult(false, events + UniversalAgentEvent("error", "No connectors are registered"))
        val definitions = tools.map { tool ->
            ToolDefinition(tool.qualifiedName, "${tool.description} Connector=${tool.connectorId} Risk=${tool.risk}", tool.parametersJsonSchema)
        }
        val messages = mutableListOf(ChatMessage(UUID.randomUUID().toString(), goal, ChatMessage.Sender.USER))
        var lastOutput = ""
        repeat(maxSteps.coerceIn(1, 32)) { step ->
            events += UniversalAgentEvent("thinking", "${provider.name} evaluating step ${step + 1}")
            val chunks = try {
                provider.generate(messages, definitions).toList()
            } catch (failure: Exception) {
                val safeMessage = failure.message?.take(500) ?: "Provider request failed."
                events += UniversalAgentEvent("error", "${provider.name} request failed: $safeMessage")
                return UniversalAgentResult(false, events, lastOutput)
            }
            val calls = chunks.filterIsInstance<StreamChunk.ToolCall>()
            val assistantText = chunks.filterIsInstance<StreamChunk.Content>().joinToString("") { it.text }
            if (calls.isEmpty()) {
                lastOutput = assistantText
                events += UniversalAgentEvent("completed", lastOutput.ifBlank { "Provider returned no text or tool call." })
                return UniversalAgentResult(lastOutput.isNotBlank(), events, lastOutput)
            }

            val callsWithIds = calls.map { call -> call.copy(id = call.id.ifBlank { UUID.randomUUID().toString() }) }
            val toolCallsJson = gson.toJson(callsWithIds.map { call ->
                mapOf(
                    "id" to call.id,
                    "type" to "function",
                    "function" to mapOf("name" to call.name, "arguments" to normalizeJson(call.arguments))
                )
            })
            messages += ChatMessage(
                id = UUID.randomUUID().toString(),
                text = assistantText,
                sender = ChatMessage.Sender.AGENT,
                providerRole = "assistant",
                toolCallsJson = toolCallsJson
            )

            for (call in callsWithIds) {
                val tool = registry.findTool(call.name)
                if (tool == null) {
                    events += UniversalAgentEvent("error", "Provider selected an unregistered tool: ${call.name}", toolName = call.name)
                    return UniversalAgentResult(false, events, lastOutput)
                }
                val invocation = ConnectorInvocation(tool = tool, argumentsJson = normalizeJson(call.arguments))
                events += UniversalAgentEvent("dispatch", "Checking policy for ${tool.connectorId}/${tool.name}", invocation.invocationId, tool.connectorId, tool.name)
                val result = registry.execute(invocation, credentials, DefaultConnectorApprovalGate, executionLog)
                if (!result.success) {
                    events += UniversalAgentEvent("blocked", result.error ?: "Connector execution failed", invocation.invocationId, tool.connectorId, tool.name)
                    return UniversalAgentResult(false, events, result.error.orEmpty())
                }
                lastOutput = result.output.take(24_000)
                events += UniversalAgentEvent("result", "Received result from ${tool.connectorId}/${tool.name}", invocation.invocationId, tool.connectorId, tool.name)
                messages += ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = lastOutput,
                    sender = ChatMessage.Sender.AGENT,
                    providerRole = "tool",
                    toolCallId = call.id
                )
            }
        }
        events += UniversalAgentEvent("error", "Cloud agent orchestration step limit reached")
        return UniversalAgentResult(false, events, lastOutput)
    }

    private fun normalizeJson(raw: String): String = runCatching {
        Json.parseToJsonElement(raw).jsonObject.toString()
    }.getOrElse { "{}" }
}
