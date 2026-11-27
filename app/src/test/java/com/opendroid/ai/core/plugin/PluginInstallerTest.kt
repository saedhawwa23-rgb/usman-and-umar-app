package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.ApprovalDecision
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.DefaultConnectorApprovalGate
import com.opendroid.ai.core.connector.UniversalToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end plugin workflow: discover, authenticate, install, execute, revoke. These tests use
 * the real [PluginRegistry], [UniversalToolRegistry] and [PluginExecutionGate]; nothing is mocked.
 */
class PluginInstallerTest {

    private val appVersion = "3.10.1"
    private fun registry() = PluginRegistry()
    private fun tools() = UniversalToolRegistry()

    private fun manifest(
        id: String = "weather.api",
        capabilities: List<String> = listOf("NETWORK_FETCH"),
    ) = PluginManifest(
        id = id,
        displayName = "Weather API",
        version = "1.0.0",
        publisher = "Weather Co",
        homepageUrl = "https://weather.example.com",
        kind = "SERVICE",
        interop = listOf("OPENAPI_31"),
        capabilities = capabilities,
        dependencies = emptyList(),
        compatibility = PluginCompatibility(minAppVersion = "3.0.0"),
    )

    private fun api() = (OpenApiImporter.import(
        """{"openapi":"3.1.0","info":{"title":"Weather"},"servers":[{"url":"https://weather.example.com"}],
           "paths":{"/now":{"get":{"operationId":"now"}}}}""",
        "weather.api",
    ) as OpenApiImporter.Result.Success).api

    @Test
    fun `installing a plugin registers its tools`() {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)

        val outcome = installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "weather.key")

        assertTrue(outcome is InstallOutcome.Installed)
        assertNotNull(tools.findTool("connector_weather.api__now"))
    }

    @Test
    fun `a freshly installed plugin is not yet callable`() {
        val plugins = registry()
        val installer = PluginInstaller(plugins, tools(), appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")

        assertFalse(plugins.get("weather.api")!!.isCallable)
    }

    @Test
    fun `a network plugin with no authentication is refused`() {
        val installer = PluginInstaller(registry(), tools(), appVersion)

        val outcome = installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.NONE)

        assertTrue(outcome is InstallOutcome.Refused)
        assertTrue((outcome as InstallOutcome.Refused).reason.contains("no authentication"))
    }

    @Test
    fun `oauth without a provider registration is refused`() {
        val installer = PluginInstaller(registry(), tools(), appVersion)

        val outcome = installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.OAUTH2_PKCE)

        assertTrue(outcome is InstallOutcome.Refused)
        assertTrue((outcome as InstallOutcome.Refused).reason.contains("provider registration"))
    }

    @Test
    fun `a non-http base url is refused`() {
        val installer = PluginInstaller(registry(), tools(), appVersion)

        val outcome = installer.install(manifest(), "ftp://x", api().tools, PluginAuthMode.API_KEY, "k")

        assertTrue(outcome is InstallOutcome.Refused)
    }

    @Test
    fun `tools belonging to another plugin are refused`() {
        val installer = PluginInstaller(registry(), tools(), appVersion)
        val foreign = api().tools.map { it.copy(connectorId = "someone.else") }

        val outcome = installer.install(manifest(), api().baseUrl, foreign, PluginAuthMode.API_KEY, "k")

        assertTrue(outcome is InstallOutcome.Refused)
    }

    @Test
    fun `approve cannot grant a capability the plugin did not declare`() {
        val installer = PluginInstaller(registry(), tools(), appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")

        val error = runCatching {
            installer.approve("weather.api", setOf(PluginCapability.FILESYSTEM_WRITE))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `revoking removes the plugin and its tools`() {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")
        installer.approve("weather.api", setOf(PluginCapability.NETWORK_FETCH))
        assertNotNull(tools.findTool("connector_weather.api__now"))

        installer.revoke("weather.api")

        assertNull(tools.findTool("connector_weather.api__now"))
        assertFalse(plugins.get("weather.api")!!.isCallable)
        assertTrue(installer.installedPluginIds().isEmpty())
    }

    // ---- The execution gate ----

    @Test
    fun `the gate denies a tool from an unapproved plugin`() = runBlocking {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")
        val gate = PluginExecutionGate(plugins)
        val invocation = ConnectorInvocation(
            tool = tools.findTool("connector_weather.api__now")!!,
            argumentsJson = "{}",
        )

        assertEquals(ApprovalDecision.DENY, gate.decide(invocation))
        assertNotNull(gate.refusalReason(invocation))
    }

    @Test
    fun `the gate allows a read-only tool from an approved plugin`() = runBlocking {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")
        installer.approve("weather.api", setOf(PluginCapability.NETWORK_FETCH))
        val gate = PluginExecutionGate(plugins)
        val invocation = ConnectorInvocation(
            tool = tools.findTool("connector_weather.api__now")!!,
            argumentsJson = "{}",
        )

        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocation))
        assertNull(gate.refusalReason(invocation))
    }

    @Test
    fun `the gate still demands approval for a sensitive plugin tool`() = runBlocking {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)
        val api = (OpenApiImporter.import(
            """{"openapi":"3.1.0","info":{"title":"W"},"servers":[{"url":"https://w.example.com"}],
               "paths":{"/pets":{"post":{"operationId":"createPet"}}}}""",
            "weather.api",
        ) as OpenApiImporter.Result.Success).api
        installer.install(manifest(), api.baseUrl, api.tools, PluginAuthMode.API_KEY, "k")
        installer.approve("weather.api", setOf(PluginCapability.NETWORK_FETCH))
        val gate = PluginExecutionGate(plugins)

        val decision = gate.decide(
            ConnectorInvocation(tool = tools.findTool("connector_weather.api__createPet")!!, argumentsJson = "{}")
        )

        assertEquals(ApprovalDecision.NEEDS_USER_APPROVAL, decision)
    }

    @Test
    fun `the gate leaves a non-plugin connector on the default rule`() = runBlocking {
        val gate = PluginExecutionGate(registry())

        // A connector that is not a registered plugin is delegated to the default gate.
        val invocation = ConnectorInvocation(
            tool = com.opendroid.ai.core.connector.ConnectorTool(
                connectorId = "colab",
                name = "read",
                description = "read",
                parametersJsonSchema = "{}",
                risk = com.opendroid.ai.core.connector.ConnectorRisk.READ_ONLY,
            ),
            argumentsJson = "{}",
        )

        assertEquals(DefaultConnectorApprovalGate.decide(invocation), gate.decide(invocation))
    }

    @Test
    fun `a disabled plugin is denied by the gate`() = runBlocking {
        val plugins = registry()
        val tools = tools()
        val installer = PluginInstaller(plugins, tools, appVersion)
        installer.install(manifest(), api().baseUrl, api().tools, PluginAuthMode.API_KEY, "k")
        installer.approve("weather.api", setOf(PluginCapability.NETWORK_FETCH))
        plugins.disable("weather.api")
        val gate = PluginExecutionGate(plugins)

        val decision = gate.decide(
            ConnectorInvocation(tool = tools.findTool("connector_weather.api__now")!!, argumentsJson = "{}")
        )

        assertEquals(ApprovalDecision.DENY, decision)
    }
}
