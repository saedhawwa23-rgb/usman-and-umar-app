package com.opendroid.ai.core.drive

import java.net.InetAddress
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoogleDriveApiNotebookTest {
    private val server = MockWebServer()
    private lateinit var api: GoogleDriveApi

    @Before fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val root = server.url("/").toString().removeSuffix("/")
        api = GoogleDriveApi(OkHttpClient(), "$root/drive/v3/files", "$root/upload/drive/v3/files", "$root/drive/v3/about")
    }

    @After fun tearDown() = server.close()

    @Test fun `uploads exact bytes and returns verified Drive and Colab links`() {
        val bytes = byteArrayOf(0, 1, 2, 10, 13, -1, 42)
        queueFolderCreation()
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"file-123","name":"demo.ipynb"}"""))
        server.enqueue(json("""{"id":"file-123","name":"demo.ipynb","mimeType":"application/x-ipynb+json","size":"7","md5Checksum":"${md5(bytes)}","webViewLink":"https://drive.google.com/file/d/file-123/view"}"""))

        val result = api.uploadNotebook("token", "demo.ipynb", bytes)

        assertEquals("file-123", result.fileId)
        assertEquals("demo.ipynb", result.fileName)
        assertEquals("https://colab.research.google.com/drive/file-123", result.colabUrl)
        assertTrue(result.contentVerified)
        assertFalse(result.reusedExisting)
        assertEquals(7L, result.sizeBytes)
        val requests = List(5) { server.takeRequest() }
        val findNameRequest = requests[2]
        val uploadRequest = requests[3]
        assertTrue(requests[1].url.encodedPath.contains("/files")) // folder create
        assertTrue(findNameRequest.url.encodedQuery.orEmpty().contains("q="))
        assertTrue(uploadRequest.url.encodedQuery.orEmpty().contains("uploadType=multipart"))
        val multipartBytes = uploadRequest.body!!.toByteArray()
        assertTrue(multipartBytes.containsSequence(bytes))
        val multipartText = multipartBytes.toString(Charsets.ISO_8859_1)
        assertTrue(multipartText.contains("application/x-ipynb+json"))
        assertTrue(multipartText.contains("\"mimeType\""))
        assertTrue(result.driveUrl!!.contains("file-123"))
    }

    @Test fun `reuses only exact name with verified identical content`() {
        val bytes = "{\"cells\":[]}".toByteArray()
        queueFolderFound()
        server.enqueue(json("""{"files":[{"id":"prior","name":"n.ipynb"}]}"""))
        server.enqueue(verified("prior", "n.ipynb", bytes))

        val result = api.uploadNotebook("token", "n.ipynb", bytes)

        assertTrue(result.reusedExisting)
        assertEquals("prior", result.fileId)
        assertEquals(3, server.requestCount)
    }

    @Test fun `different content under existing name uploads deterministic free suffix`() {
        val bytes = "new notebook".toByteArray()
        queueFolderFound()
        server.enqueue(json("""{"files":[{"id":"old","name":"n.ipynb"}]}"""))
        server.enqueue(verified("old", "n.ipynb", "old".toByteArray()))
        server.enqueue(json("""{"id":"new-id","name":"n (2).ipynb"}"""))
        server.enqueue(verified("new-id", "n (2).ipynb", bytes))

        val result = api.uploadNotebook("token", "n.ipynb", bytes)

        assertEquals("n (2).ipynb", result.fileName)
        assertFalse(result.reusedExisting)
        val calls = List(5) { server.takeRequest() }
        assertTrue(calls[3].body!!.toByteArray().toString(Charsets.UTF_8).contains("n (2).ipynb"))
    }

    @Test fun `verification mismatch fails instead of reporting success`() {
        val bytes = "expected".toByteArray()
        queueFolderFound()
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"bad","name":"n.ipynb"}"""))
        server.enqueue(json("""{"id":"bad","name":"n.ipynb","mimeType":"application/x-ipynb+json","size":"8","md5Checksum":"00000000000000000000000000000000"}"""))
        assertThrows(IllegalStateException::class.java) { api.uploadNotebook("token", "n.ipynb", bytes) }
    }

    @Test fun `HTTP errors propagate as DriveApiException`() {
        queueFolderFound()
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"quota exceeded"}}""").build())
        val error = assertThrows(DriveApiException::class.java) { api.uploadNotebook("token", "n.ipynb", byteArrayOf(1)) }
        assertEquals(403, error.statusCode)
    }

    @Test fun `network failures propagate without being disguised as success`() {
        server.close()
        assertThrows(java.io.IOException::class.java) { api.uploadNotebook("token", "n.ipynb", byteArrayOf(1)) }
    }

    private fun queueFolderCreation() {
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"folder-id"}"""))
    }

    private fun queueFolderFound() = server.enqueue(json("""{"files":[{"id":"folder-id","name":"USMAN_AND_UMAR_Notebooks"}]}"""))
    private fun verified(id: String, name: String, bytes: ByteArray) = json(
        """{"id":"$id","name":"$name","mimeType":"application/x-ipynb+json","size":"${bytes.size}","md5Checksum":"${md5(bytes)}","webViewLink":"https://drive.google.com/file/d/$id/view"}"""
    )
    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun md5(bytes: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }
}
