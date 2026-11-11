package com.opendroid.ai.core.connector

import android.content.Context
import android.content.Intent
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService

/** Typed outcome for an on-device operation. */
data class DeviceResult(
    val success: Boolean,
    val message: String,
    val error: String? = null
) {
    internal fun asConnectorResult(): ConnectorResult =
        if (success) ConnectorResult(true, output = message)
        else ConnectorResult(false, error = error ?: message)
}

/** Local allowlisted device controls; this connector never uses cloud credentials or a shell. */
class DeviceControlConnector(
    private val context: Context
) : UniversalConnector {
    override val id: String = "device"
    override val displayName: String = "On-device Controls"
    override val kind: ConnectorKind = ConnectorKind.DEVICE

    override fun tools(): List<ConnectorTool> = listOf(
        tool("open_app", "Open an installed app by its package name.",
            """{"type":"object","properties":{"packageName":{"type":"string","minLength":1,"maxLength":255,"pattern":"^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"}},"required":["packageName"],"additionalProperties":false}""", ConnectorRisk.SENSITIVE),
        tool("tap", "Tap a visible accessibility element by its displayed text, or by screen coordinates.",
            """{"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":300},"x":{"type":"number","minimum":0,"maximum":10000},"y":{"type":"number","minimum":0,"maximum":10000}},"oneOf":[{"required":["text"]},{"required":["x","y"]}],"additionalProperties":false}""", ConnectorRisk.SENSITIVE),
        tool("type_text", "Type text into the visible accessibility element matching its displayed text.",
            """{"type":"object","properties":{"target":{"type":"string","minLength":1,"maxLength":300},"text":{"type":"string","maxLength":10000}},"required":["target","text"],"additionalProperties":false}""", ConnectorRisk.SENSITIVE),
        tool("read_screen", "Read text exposed by the active screen's accessibility tree.",
            """{"type":"object","properties":{},"additionalProperties":false}""", ConnectorRisk.READ_ONLY)
    )

    private fun tool(name: String, description: String, schema: String, risk: ConnectorRisk) =
        ConnectorTool(id, "device.$name", description, schema, risk, toolNameOverride = "device.$name")

    override suspend fun execute(
        invocation: ConnectorInvocation,
        credentials: ConnectorCredentialResolver
    ): ConnectorResult {
        if (KillSwitch.isEngaged) return blockedResult().asConnectorResult()
        if (invocation.tool.connectorId != id) return failure("Tool does not belong to this connector.").asConnectorResult()

        val args = try {
            JsonParser.parseString(invocation.argumentsJson).takeIf { it.isJsonObject }?.asJsonObject
                ?: return failure("Tool arguments must be a JSON object.").asConnectorResult()
        } catch (_: Exception) {
            return failure("Tool arguments must be valid JSON.").asConnectorResult()
        }
        val result = try {
            when (invocation.tool.name) {
                "device.open_app" -> openApp(args)
                "device.tap" -> tap(args)
                "device.type_text" -> typeText(args)
                "device.read_screen" -> readScreen(args)
                else -> failure("Device tool is not allowlisted.")
            }
        } catch (_: Exception) {
            failure("Device action failed.")
        }
        return result.asConnectorResult()
    }

    private fun openApp(args: JsonObject): DeviceResult {
        if (!hasOnly(args, "packageName")) return failure("Invalid arguments for device.open_app.")
        val packageName = args.string("packageName") ?: return failure("A valid packageName is required.")
        if (packageName.length > 255 || !PACKAGE_NAME.matches(packageName)) return failure("Invalid packageName.")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure("App is not installed or has no launch activity.")
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            DeviceResult(true, "Launched app $packageName.")
        } catch (_: Exception) {
            failure("Unable to launch app $packageName.")
        }
    }

    private fun tap(args: JsonObject): DeviceResult {
        val service = OpenDroidAccessibilityService.getInstance() ?: return accessibilityOff()
        return when {
            hasOnly(args, "text") -> {
                val text = args.string("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 }
                    ?: return failure("A non-empty text target (maximum 300 characters) is required.")
                if (service.findAndClick(text)) DeviceResult(true, "Tapped accessibility target.")
                else failure("No tappable accessibility target matched the supplied text.")
            }
            hasOnly(args, "x", "y") -> {
                val x = args.number("x") ?: return failure("Coordinates must be finite numbers in the range 0..10000.")
                val y = args.number("y") ?: return failure("Coordinates must be finite numbers in the range 0..10000.")
                if (!x.isFinite() || !y.isFinite() || x !in 0.0..10000.0 || y !in 0.0..10000.0)
                    return failure("Coordinates must be finite numbers in the range 0..10000.")
                if (service.clickCoordinates(x.toFloat(), y.toFloat())) DeviceResult(true, "Tap gesture dispatched.")
                else failure("Tap gesture was not dispatched.")
            }
            else -> failure("Provide either text or both x and y coordinates.")
        }
    }

    private fun typeText(args: JsonObject): DeviceResult {
        if (!hasOnly(args, "target", "text")) return failure("Invalid arguments for device.type_text.")
        val target = args.string("target")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 }
            ?: return failure("A non-empty target (maximum 300 characters) is required.")
        val text = args.string("text")?.takeIf { it.length <= 10000 }
            ?: return failure("Text must be a string of at most 10000 characters.")
        val service = OpenDroidAccessibilityService.getInstance() ?: return accessibilityOff()
        return if (service.findAndType(target, text)) DeviceResult(true, "Text entered into the matching accessibility target.")
        else failure("No editable accessibility target matched the supplied text.")
    }

    private fun readScreen(args: JsonObject): DeviceResult {
        if (!args.entrySet().isEmpty()) return failure("device.read_screen does not accept arguments.")
        val service = OpenDroidAccessibilityService.getInstance() ?: return accessibilityOff()
        return DeviceResult(true, service.getScreenText())
    }

    private fun hasOnly(args: JsonObject, vararg names: String): Boolean =
        args.keySet() == names.toSet()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.number(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun accessibilityOff() = failure(ACCESSIBILITY_ERROR)
    private fun failure(message: String) = DeviceResult(false, message, message)
    private fun blockedResult() = failure(KILL_SWITCH_ERROR)

    companion object {
        const val ACCESSIBILITY_ERROR = "Accessibility service is not enabled. Enable it in Settings > Accessibility."
        const val KILL_SWITCH_ERROR = "Kill switch engaged: all device actions are blocked."
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}
