package com.opendroid.ai.core.connector

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionRiskPolicy
import com.opendroid.ai.core.agent.ActionSchema
import kotlinx.coroutines.withTimeout

/**
 * Adapter from the existing deterministic Android action catalog to the
 * connector/tool-calling pipeline. It deliberately delegates all validation,
 * permissions, device checks, and concrete side effects to ActionDispatcher.
 */
class LocalActionConnector(
    private val context: Context,
    private val dispatcher: ActionDispatcher,
) : UniversalConnector {
    override val id: String = "local_android_actions"
    override val displayName: String = "On-device Android actions"
    override val kind: ConnectorKind = ConnectorKind.CLI

    private val definitions: List<ConnectorTool> = ActionSchema.ALL_ACTIONS.map { definition ->
        val risk = when (ActionRiskPolicy.forDefinition(definition)) {
            ActionRisk.READ_ONLY -> ConnectorRisk.READ_ONLY
            ActionRisk.REVERSIBLE -> ConnectorRisk.REVERSIBLE
            else -> ConnectorRisk.SENSITIVE
        }
        ConnectorTool(
            connectorId = id,
            name = definition.name,
            description = definition.description,
            parametersJsonSchema = schemaFor(definition),
            risk = risk,
            category = "Android & Phone",
        )
    }

    override fun tools(): List<ConnectorTool> = definitions

    override suspend fun execute(
        invocation: ConnectorInvocation,
        credentials: ConnectorCredentialResolver,
    ): ConnectorResult {
        if (invocation.tool.connectorId != id) {
            return ConnectorResult(false, error = "Invocation is not addressed to the local Android connector")
        }
        val args = runCatching {
            Gson().fromJson(invocation.argumentsJson, JsonObject::class.java)
        }.getOrNull() ?: return ConnectorResult(false, error = "Tool arguments must be a JSON object")
        val params = args.entrySet().associate { (key, value) ->
            key to when {
                value.isJsonNull -> ""
                value.isJsonPrimitive -> value.asJsonPrimitive.toString().removeSurrounding("\"")
                else -> value.toString()
            }
        }
        val result = try {
            withTimeout(invocation.toolTimeoutMs()) {
                dispatcher.execute(invocation.tool.name, params, context)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            return ConnectorResult(false, error = "Android action timed out")
        } catch (failure: Throwable) {
            return ConnectorResult(false, error = failure.message ?: "Android action failed")
        }
        return result.toConnectorResult()
    }

    private fun ConnectorInvocation.toolTimeoutMs(): Long =
        ActionSchema.getAction(tool.name)?.let { 30_000L } ?: 30_000L

    private fun ActionResult.toConnectorResult(): ConnectorResult = when (this) {
        is ActionResult.Success -> ConnectorResult(true, output = data ?: "")
        else -> ConnectorResult(false, output = data ?: "", error = error ?: "Android action did not complete")
    }

    private fun schemaFor(definition: com.opendroid.ai.core.agent.ActionDefinition): String {
        val properties = definition.params.joinToString(",") { param ->
            val type = when (param.type.name) {
                "INT" -> "integer"
                "BOOLEAN" -> "boolean"
                else -> "string"
            }
            "\"${param.name}\":{\"type\":\"$type\",\"description\":${Gson().toJson(param.description)}}"
        }
        val required = definition.params.filter { it.required }.joinToString(",") { "\"${it.name}\"" }
        return "{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required],\"additionalProperties\":false}"
    }
}
