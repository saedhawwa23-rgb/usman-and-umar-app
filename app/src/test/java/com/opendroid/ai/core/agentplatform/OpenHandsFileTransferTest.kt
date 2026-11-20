package com.opendroid.ai.core.agentplatform

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

class OpenHandsFileTransferTest {
    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `upload uses documented multipart endpoint and session key header`() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"success\":true}").build())
        val client = OpenHandsClient(server.url("/").toString().trimEnd('/'), "test-session-key")

        val result = client.uploadFile("/workspace/input.txt", "input.txt", "hello".toByteArray())

        assertTrue("Upload should succeed only after a real HTTP 2xx response", result is PlatformResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/file/upload", request.url.encodedPath)
        assertEquals("/workspace/input.txt", request.url.queryParameter("path"))
        assertEquals("test-session-key", request.headers["X-Session-API-Key"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("name=\"file\"; filename=\"input.txt\""))
        assertTrue(body.contains("hello"))
    }

    @Test
    fun `download uses path query and returns exact file bytes`() {
        val expected = byteArrayOf(0, 1, 2, 127, -1)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(expected)).build())
        val client = OpenHandsClient(server.url("/").toString().trimEnd('/'), "test-session-key")

        val result = client.downloadFile("/workspace/result.bin")

        assertTrue(result is PlatformResult.Success)
        assertArrayEquals(expected, (result as PlatformResult.Success).value)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/file/download", request.url.encodedPath)
        assertEquals("/workspace/result.bin", request.url.queryParameter("path"))
        assertEquals("test-session-key", request.headers["X-Session-API-Key"])
    }

    @Test
    fun `invalid traversal path is rejected before network access`() {
        val client = OpenHandsClient(server.url("/").toString().trimEnd('/'), "test-session-key")

        val result = client.downloadFile("/workspace/../secrets.txt")

        assertTrue(result is PlatformResult.Failure)
        assertEquals(0, server.requestCount)
    }

    /**
     * Exercises the shipping client against a genuinely running agent server, not a mock.
     *
     * Skipped unless an operator supplies a live server, because the roundtrip needs real server
     * storage and a real session key:
     *
     * ```
     * OH_E2E_BASE_URL=http://127.0.0.1:61234 \
     * OH_E2E_SESSION_KEY=<session key> \
     * ./gradlew :app:testDebugUnitTest --tests '*OpenHandsLiveFileTransferTest*'
     * ```
     */
    @Test
    fun `live server roundtrip returns identical bytes`() {
        val baseUrl = System.getenv("OH_E2E_BASE_URL")
        val sessionKey = System.getenv("OH_E2E_SESSION_KEY")
        assumeTrue("OH_E2E_BASE_URL not set; skipping live transfer", !baseUrl.isNullOrBlank())
        assumeTrue("OH_E2E_SESSION_KEY not set; skipping live transfer", !sessionKey.isNullOrBlank())

        val client = OpenHandsClient(baseUrl!!, sessionKey!!)
        val remotePath = "/tmp/oh-live-transfer-${System.nanoTime()}.bin"
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1, -2, 42)

        val upload = client.uploadFile(remotePath, "live.bin", payload)
        assertTrue("Upload against a live server must succeed: $upload", upload is PlatformResult.Success)

        val download = client.downloadFile(remotePath)
        assertTrue("Download against a live server must succeed: $download", download is PlatformResult.Success)
        assertArrayEquals(payload, (download as PlatformResult.Success).value)
    }
}
