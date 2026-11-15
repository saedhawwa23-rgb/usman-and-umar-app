package com.opendroid.ai.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {

    /** Records what each tool asked the platform to do, without touching Android. */
    private class FakeEnvironment : AgentToolEnvironment {
        val calls = mutableListOf<String>()
        var linuxResult = "linux-ok"
        var appResult = "app-ok"
        var urlResult = "url-ok"
        var localAppResult = "local-app-ok"

        override fun listInstalledPackages(thirdPartyOnly: Boolean): String {
            calls += "listInstalledPackages($thirdPartyOnly)"
            return "packages-ok"
        }

        override fun deviceProperty(command: String): String {
            calls += "deviceProperty($command)"
            return "device-ok"
        }

        override fun batteryStatus(): String { calls += "batteryStatus"; return "battery-ok" }

        override fun networkStatus(): String { calls += "networkStatus"; return "network-ok" }

        override fun screenBrightness(): String { calls += "screenBrightness"; return "brightness-ok" }

        override fun displaySize(): String { calls += "displaySize"; return "size-ok" }

        override suspend fun runLinux(command: String): String { calls += "runLinux($command)"; return linuxResult }

        override suspend fun openApp(target: String): String { calls += "openApp($target)"; return appResult }

        override suspend fun openUrl(url: String): String { calls += "openUrl($url)"; return urlResult }

        override suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String): String {
            calls += "useLocalApp($appName,$packageName,$prompt)"
            return localAppResult
        }
    }

    private fun call(action: String, command: String? = null, url: String? = null, app: String? = null, prompt: String? = null) =
        ChatToolCall(action = action, command = command, url = url, appName = app, prompt = prompt, rawJson = "{}")

    @Test
    fun `standard registry exposes every action the approval gate previously supported`() {
        val registry = AgentToolRegistry.standard()
        listOf(
            "shell", "pm_list_packages", "list_packages", "open_app", "launch_app",
            "open_url", "open_uri", "use_local_app", "device_info", "android_device_info",
            "battery_status", "get_battery_status", "network_status", "get_network_status",
            "screen_brightness",
        ).forEach { name ->
            assertNotNull("Expected a tool for '$name'", registry.find(name))
        }
    }

    @Test
    fun `aliases resolve to the same tool as the canonical id`() {
        val registry = AgentToolRegistry.standard()
        assertSame(registry.find("pm_list_packages"), registry.find("list_packages"))
        assertSame(registry.find("open_app"), registry.find("launch_app"))
        assertSame(registry.find("open_url"), registry.find("open_uri"))
        assertSame(registry.find("battery_status"), registry.find("get_battery_status"))
    }

    @Test
    fun `all collapses aliases so each tool is reported once`() {
        val registry = AgentToolRegistry.standard()
        val ids = registry.all().map { it.id }
        assertEquals(ids.distinct(), ids)
        // 9 canonical tools: shell, pm_list_packages, open_app, open_url, use_local_app,
        // device_info, battery_status, network_status, screen_brightness.
        assertEquals(9, ids.size)
    }

    @Test
    fun `action lookup is case and whitespace insensitive`() {
        val registry = AgentToolRegistry.standard()
        assertNotNull(registry.find("  OPEN_APP  "))
    }

    @Test
    fun `unknown action fails with a message naming it and executes nothing`() = runBlocking {
        val env = FakeEnvironment()
        val failure = runCatching { AgentToolRegistry.standard().execute(call("definitely_not_a_tool"), env) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("definitely_not_a_tool"))
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `pm list packages maps to the environment and honours the third-party flag`() = runBlocking {
        val env = FakeEnvironment()
        val registry = AgentToolRegistry.standard()
        registry.execute(call("shell", command = "pm list packages"), env)
        registry.execute(call("shell", command = "pm list packages -3"), env)
        assertEquals(listOf("listInstalledPackages(false)", "listInstalledPackages(true)"), env.calls)
    }

    @Test
    fun `shell rejects a command it cannot support instead of guessing`() = runCatching {
        runBlocking { AgentToolRegistry.standard().execute(call("shell", command = "rm -rf /"), FakeEnvironment()) }
    }.let { failure ->
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("Unsupported shell action"))
    }

    @Test
    fun `shell blocks chaining and redirection for linux commands`() {
        val env = FakeEnvironment()
        listOf("python3 -c 'x' ; rm -rf /", "git clone a && rm -rf b", "npm install > /etc/passwd").forEach { command ->
            val failure = runCatching { runBlocking { AgentToolRegistry.standard().execute(call("shell", command = command), env) } }
            assertTrue("Expected '$command' to be rejected", failure.isFailure)
        }
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `shell runs a simple linux command through the environment`() = runBlocking {
        val env = FakeEnvironment()
        val result = AgentToolRegistry.standard().execute(call("shell", command = "python3 --version"), env)
        assertEquals("linux-ok", result)
        assertEquals(listOf("runLinux(python3 --version)"), env.calls)
    }

    @Test
    fun `open url requires a target`() {
        val failure = runCatching { runBlocking { AgentToolRegistry.standard().execute(call("open_url"), FakeEnvironment()) } }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("URL"))
    }

    @Test
    fun `registering a duplicate name is refused`() {
        val registry = AgentToolRegistry.standard()
        val duplicate = AgentTool(id = "open_app", description = "duplicate") { _, _ -> "" }
        val failure = runCatching { registry.register(duplicate) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("already registered"))
    }

    @Test
    fun `a tool added at runtime becomes callable without touching any view model`() = runBlocking {
        val registry = AgentToolRegistry.standard()
        registry.register(
            AgentTool(id = "custom_echo", aliases = setOf("echo"), description = "test tool") { c, _ -> "echo:${c.command}" }
        )
        val result = registry.execute(call("echo", command = "hi"), FakeEnvironment())
        assertEquals("echo:hi", result)
    }

    @Test
    fun `blank tool ids are refused`() {
        assertTrue(runCatching { AgentToolRegistry(listOf(AgentTool(id = " ", description = "x") { _, _ -> "" })) }.isFailure)
    }

    @Test
    fun `every standard tool is discoverable by its own id`() {
        val registry = AgentToolRegistry.standard()
        registry.all().forEach { tool ->
            assertNotNull(registry.find(tool.id))
            assertNull(registry.find("${tool.id}_missing"))
        }
    }
}
