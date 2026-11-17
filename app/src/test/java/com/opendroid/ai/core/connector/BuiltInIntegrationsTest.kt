package com.opendroid.ai.core.connector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BuiltInIntegrationsTest {

    private val noCredentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
    }

    private fun tool(connector: UniversalConnector, name: String): ConnectorTool =
        connector.tools().first { it.name == name }

    // ---- Time ----

    @Test
    fun timeNowReturnsClockValue() = runBlocking {
        val integration = BuiltInTimeIntegration(clock = { 1_700_000_000_000L }, zone = { ZoneId.of("UTC") })
        val result = integration.execute(ConnectorInvocation(tool = tool(integration, "now"), argumentsJson = "{}"), noCredentials)
        assertTrue(result.success)
        assertTrue(result.output.contains("1700000000000"))
        assertTrue(result.output.contains("2023-11-14"))
    }

    @Test
    fun timeFormatRejectsUnknownZone() = runBlocking {
        val integration = BuiltInTimeIntegration(zone = { ZoneId.of("UTC") })
        val result = integration.execute(
            ConnectorInvocation(
                tool = tool(integration, "format"),
                argumentsJson = """{"epochMs":0,"pattern":"yyyy","zone":"Mars/Olympus"}""",
            ),
            noCredentials,
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown time zone"))
    }

    @Test
    fun timeAddDurationIsExact() = runBlocking {
        val integration = BuiltInTimeIntegration(zone = { ZoneId.of("UTC") })
        val result = integration.execute(
            ConnectorInvocation(
                tool = tool(integration, "add_duration"),
                argumentsJson = """{"epochMs":1000,"seconds":90}""",
            ),
            noCredentials,
        )
        assertTrue(result.success)
        assertTrue(result.output.contains("91000"))
    }

    // ---- Text ----

    @Test
    fun textTransformCoversSlugAndLength() = runBlocking {
        val integration = BuiltInTextIntegration()
        val slug = integration.execute(
            ConnectorInvocation(tool = tool(integration, "transform"), argumentsJson = """{"text":"Hello, World!","operation":"slug"}"""),
            noCredentials,
        )
        assertTrue(slug.success)
        assertTrue(slug.output.contains("hello-world"))
        val length = integration.execute(
            ConnectorInvocation(tool = tool(integration, "transform"), argumentsJson = """{"text":"abcd","operation":"length"}"""),
            noCredentials,
        )
        assertTrue(length.output.contains("4"))
    }

    @Test
    fun textReplaceCountsOccurrences() = runBlocking {
        val integration = BuiltInTextIntegration()
        val result = integration.execute(
            ConnectorInvocation(
                tool = tool(integration, "replace"),
                argumentsJson = """{"text":"a-b-a-b","find":"a","replaceWith":"X"}""",
            ),
            noCredentials,
        )
        assertTrue(result.success)
        assertTrue(result.output.contains("X-b-X-b"))
        assertTrue(result.output.contains("2"))
    }

    @Test
    fun textSplitRejectsEmptySeparator() = runBlocking {
        val integration = BuiltInTextIntegration()
        val result = integration.execute(
            ConnectorInvocation(tool = tool(integration, "split"), argumentsJson = """{"text":"abc","separator":""}"""),
            noCredentials,
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("separator"))
    }

    // ---- HTTP ----

    @Test
    fun httpGetReportsRealStatusAndBody() = runBlocking {
        val integration = BuiltInHttpIntegration(transport = HttpFetchTransport { _, _ ->
            HttpFetchResponse(200, """{"ok":true}""")
        })
        val result = integration.execute(
            ConnectorInvocation(tool = tool(integration, "get"), argumentsJson = """{"url":"https://example.test/health"}"""),
            noCredentials,
        )
        assertTrue(result.success)
        assertTrue(result.output.contains("200"))
        assertTrue(result.output.contains("ok"))
    }

    @Test
    fun httpRefusesNonHttpScheme() = runBlocking {
        var transportCalled = false
        val integration = BuiltInHttpIntegration(transport = HttpFetchTransport { _, _ ->
            transportCalled = true
            HttpFetchResponse(200, "")
        })
        val result = integration.execute(
            ConnectorInvocation(tool = tool(integration, "get"), argumentsJson = """{"url":"file:///etc/passwd"}"""),
            noCredentials,
        )
        assertFalse(result.success)
        assertFalse("transport must not run for a non-http URL", transportCalled)
    }

    @Test
    fun httpSurfacesTransportFailure() = runBlocking {
        val integration = BuiltInHttpIntegration(transport = HttpFetchTransport { _, _ -> throw java.io.IOException("no route to host") })
        val result = integration.execute(
            ConnectorInvocation(tool = tool(integration, "status"), argumentsJson = """{"url":"https://unreachable.test"}"""),
            noCredentials,
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("no route to host"))
    }

    @Test
    fun allThreeBuiltInsAreRegisteredWithUniqueIds() {
        val integrations = builtInIntegrations()
        assertEquals(3, integrations.size)
        assertEquals(integrations.map { it.id }.toSet().size, integrations.size)
        assertTrue(integrations.all { it.tools().isNotEmpty() })
    }
}
