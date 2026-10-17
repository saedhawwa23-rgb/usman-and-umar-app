package com.opendroid.ai.core.connector

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HttpConnectorTest {
    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    @After fun stop() { server.close() }

    @Test
    fun `path parameters are encoded and authorization stays in header`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{\"success\":true}").build())
        val tool = ConnectorTool(
            connectorId = "cloudflare",
            name = "list_deployments",
            description = "List deployments",
            parametersJsonSchema = "{\"type\":\"object\"}",
            credential = ConnectorCredentialRef("cf-token")
        )
        val connector = HttpConnector(
            id = "cloudflare",
            displayName = "Cloudflare Pages",
            kind = ConnectorKind.CLOUD,
            baseUrl = server.url("/client/v4").toString().trimEnd('/'),
            definitions = listOf(tool),
            methodByTool = mapOf(tool.name to "GET"),
            pathByTool = mapOf(tool.name to "/accounts/{accountId}/pages/projects/{projectName}/deployments")
        )
        val resolver = object : ConnectorCredentialResolver {
            override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block("test-token")
        }
        val result = connector.execute(
            ConnectorInvocation(tool = tool, argumentsJson = "{\"accountId\":\"account 1\",\"projectName\":\"demo\"}"),
            resolver
        )
        assertTrue(result.success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/client/v4/accounts/account%201/pages/projects/demo/deployments", request.url.encodedPath)
        assertEquals("Bearer test-token", request.headers["Authorization"])
    }
}
