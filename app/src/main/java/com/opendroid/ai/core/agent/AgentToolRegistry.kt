package com.opendroid.ai.core.agent

/**
 * The Android operations a tool may perform. Implemented once by the caller that holds a
 * [android.content.Context]; tools themselves stay free of Android types so the registry can be
 * exercised on the JVM.
 *
 * Every method is read-only or opens an existing app/URL, matching the action set that the
 * approval gate already exposed before this registry existed.
 */
interface AgentToolEnvironment {
    fun listInstalledPackages(thirdPartyOnly: Boolean): String
    fun deviceProperty(command: String): String
    fun batteryStatus(): String
    fun networkStatus(): String
    fun screenBrightness(): String
    fun displaySize(): String
    suspend fun runLinux(command: String): String
    suspend fun openApp(target: String): String
    suspend fun openUrl(url: String): String
    suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String): String
}

/**
 * One executable action an AI model (or an authorised remote device) may request.
 *
 * [id] is the canonical name; [aliases] are the historical names kept so transcripts written by
 * older prompts keep working. [requiresApproval] records whether the action must pass the human
 * approval gate - the registry itself never approves anything.
 */
data class AgentTool(
    val id: String,
    val aliases: Set<String> = emptySet(),
    val description: String,
    val requiresApproval: Boolean = true,
    val execute: suspend (ChatToolCall, AgentToolEnvironment) -> String,
)

/**
 * The single place that decides which tool names exist and what they run.
 *
 * Previously the chat agent's tool set lived in one hard-coded `when` block inside
 * `ChatViewModel`, so no other caller could execute a tool and adding one meant editing the
 * view model. The registry makes the set data, so the chat agent, the remote-control executor
 * and OpenSwarm all run the same code with the same names.
 */
class AgentToolRegistry(tools: Iterable<AgentTool> = emptyList()) {

    private val byName = linkedMapOf<String, AgentTool>()

    init {
        tools.forEach(::register)
    }

    fun register(tool: AgentTool) {
        require(tool.id.isNotBlank()) { "A tool id must not be blank" }
        val names = tool.aliases + tool.id
        names.forEach { name ->
            require(name !in byName) { "Tool name already registered: $name" }
        }
        names.forEach { byName[it] = tool }
    }

    fun find(action: String): AgentTool? = byName[action.trim().lowercase()]

    fun all(): List<AgentTool> = byName.values.distinct()

    /** Runs [call]'s action, or fails with a message naming the unknown action. */
    suspend fun execute(call: ChatToolCall, environment: AgentToolEnvironment): String {
        val tool = find(call.action) ?: error("Unsupported tool action '${call.action}'. No action was executed.")
        return tool.execute(call, environment)
    }

    companion object {
        /**
         * The built-in tool set. Behaviour is identical to the action set the approval gate
         * exposed before this registry existed; only its shape changed from a `when` to data.
         */
        fun standard(): AgentToolRegistry = AgentToolRegistry(
            listOf(
                AgentTool(
                    id = "shell",
                    description = "Run one simple, read-only Android query or an explicitly approved Linux command.",
                ) { call, env ->
                    val command = call.command?.trim().orEmpty()
                    require(command.isNotBlank()) { "The proposed shell action has no command." }
                    when {
                        Regex("^pm\\s+list\\s+packages(?:\\s+-3)?$").matches(command) ->
                            env.listInstalledPackages(thirdPartyOnly = command.endsWith("-3"))
                        command in setOf("getprop ro.build.version.release", "getprop ro.build.version.sdk", "getprop ro.product.model") ->
                            env.deviceProperty(command)
                        command == "dumpsys battery" -> env.batteryStatus()
                        command == "settings get system screen_brightness" -> env.screenBrightness()
                        command == "wm size" -> env.displaySize()
                        Regex("^(python3?|python[0-9.]*|git|npm|node)(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> {
                            require(!Regex("[;&|<>`\\n\\r$]").containsMatchIn(command)) {
                                "Only one simple Linux command may be approved at a time; shell chaining and redirection are blocked."
                            }
                            env.runLinux(command)
                        }
                        command.startsWith("open app ", ignoreCase = true) ->
                            env.openApp(command.substringAfter("open app ").trim())
                        command.startsWith("open url ", ignoreCase = true) ->
                            env.openUrl(command.substringAfter("open url ").trim())
                        else -> error("Unsupported shell action. Native mode supports read-only Android queries, 'pm list packages', 'open app …', and 'open url …'. Python/Git/npm/Node tasks require Termux.")
                    }
                },
                AgentTool(
                    id = "pm_list_packages",
                    aliases = setOf("list_packages"),
                    description = "List installed application package names.",
                ) { _, env -> env.listInstalledPackages(thirdPartyOnly = false) },
                AgentTool(
                    id = "open_app",
                    aliases = setOf("launch_app"),
                    description = "Open an installed app by label or package name.",
                ) { call, env ->
                    val target = call.packageName ?: call.appName
                    require(!target.isNullOrBlank()) { "Specify an app name or package name." }
                    env.openApp(target)
                },
                AgentTool(
                    id = "open_url",
                    aliases = setOf("open_uri"),
                    description = "Open an HTTP or HTTPS URL in a browser.",
                ) { call, env ->
                    val url = call.url ?: call.appName
                    require(!url.isNullOrBlank()) { "Specify an HTTP or HTTPS URL." }
                    env.openUrl(url)
                },
                AgentTool(
                    id = "use_local_app",
                    description = "Delegate a prompt to an installed on-device AI app.",
                ) { call, env ->
                    env.useLocalApp(
                        call.appName,
                        call.packageName,
                        call.prompt ?: call.command ?: error("Specify the prompt to send to the local AI app."),
                    )
                },
                AgentTool(
                    id = "device_info",
                    aliases = setOf("android_device_info"),
                    description = "Report the Android release version.",
                ) { _, env -> env.deviceProperty("getprop ro.build.version.release") },
                AgentTool(
                    id = "battery_status",
                    aliases = setOf("get_battery_status"),
                    description = "Report battery charge and charging state.",
                ) { _, env -> env.batteryStatus() },
                AgentTool(
                    id = "network_status",
                    aliases = setOf("get_network_status"),
                    description = "Report the active network transport and validation state.",
                ) { _, env -> env.networkStatus() },
                AgentTool(
                    id = "screen_brightness",
                    description = "Report the current screen brightness setting.",
                ) { _, env -> env.screenBrightness() },
            )
        )
    }
}
