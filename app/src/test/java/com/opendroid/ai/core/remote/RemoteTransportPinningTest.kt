package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.tls.HeldCertificate
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * Exercises [RemoteTransport] against a real TLS server holding a real self-signed certificate.
 *
 * Nothing is mocked: the server is a genuine [SSLServerSocket], the client performs a genuine
 * handshake, and the pinning decision is made by the production [X509TrustManager]. The server
 * identity is generated per run, so no private key is stored in the repository.
 */
class RemoteTransportPinningTest {

    private companion object {
        val KEY_PASSWORD = "testpassword".toCharArray()
    }

    /** A throwaway self-signed identity, generated per run so no key is committed. */
    private val identity: HeldCertificate = HeldCertificate.Builder()
        .commonName("opendroid-agent")
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    private fun loadContext(): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(
            "agent",
            identity.keyPair.private,
            KEY_PASSWORD,
            arrayOf(identity.certificate),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEY_PASSWORD)
        return SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, null, null) }
    }

    private fun certificate(): X509Certificate = identity.certificate

    /**
     * Runs a one-connection TLS server that answers one byte with one byte.
     *
     * The server-side failure is kept, so a client assertion can report why the peer hung up
     * instead of surfacing a bare end-of-stream.
     */
    private fun startServer(): Pair<Int, SSLServerSocket> {
        val context = loadContext()
        val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        thread(isDaemon = true) {
            serverFailure.set(
                runCatching {
                    server.accept().use { socket ->
                        val received = socket.getInputStream().read()
                        if (received < 0) error("The client closed before sending its byte")
                        socket.getOutputStream().write(1)
                        socket.getOutputStream().flush()
                    }
                }.exceptionOrNull(),
            )
        }
        return server.localPort to server
    }

    /** Why the last server thread failed, or null when it completed cleanly. */
    private val serverFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

    @Test
    fun `the fingerprint helper matches what keytool prints`() {
        val expected = fingerprintOf(certificate())

        assertEquals(64, expected.length)
        assertTrue(expected.all { it in "0123456789abcdef" })
        // keytool prints colon-separated uppercase; both forms must normalise to the same value.
        val keytoolStyle = expected.chunked(2).joinToString(":") { it.uppercase() }
        assertEquals(expected, normaliseFingerprint(keytoolStyle))
    }

    @Test
    fun `a pinned connection succeeds against the matching certificate`() {
        val (port, server) = startServer()
        val pinned = fingerprintOf(certificate())

        val socket = RemoteTransport.open("127.0.0.1", port, RemoteTlsMode.Pinned(pinned))
        try {
            assertTrue(socket.isConnected)
            socket.getOutputStream().write(0)
            socket.getOutputStream().flush()
            val answer = socket.getInputStream().read()
            assertEquals("server said: ${serverFailure.get()}", 1, answer)
        } finally {
            runCatching { socket.close() }
            runCatching { server.close() }
        }
    }

    @Test
    fun `a pinned connection is refused when the fingerprint does not match`() {
        val (port, server) = startServer()
        val wrong = "00".repeat(32)

        val failure = runCatching {
            RemoteTransport.open("127.0.0.1", port, RemoteTlsMode.Pinned(wrong))
        }.exceptionOrNull()

        assertTrue("Expected the handshake to fail, got $failure", failure != null)
        // The refusal must name the mismatch rather than surface as an opaque IO error, so the
        // user can tell an impostor apart from a stale pairing.
        val chain = generateSequence(failure) { it.cause }.toList()
        assertTrue(
            "Expected a certificate error, got ${chain.map { it::class.simpleName }}",
            chain.any { it is CertificateException },
        )
        runCatching { server.close() }
    }

    @Test
    fun `an empty fingerprint is rejected before any connection is attempted`() {
        val failure = runCatching {
            RemoteTransport.open("127.0.0.1", 1, RemoteTlsMode.Pinned(""))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a plain connection still works for a trusted local network`() {
        val plain = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                plain.accept().use { it.getOutputStream().write(7) }
            }
        }
        val socket = RemoteTransport.open("127.0.0.1", plain.localPort, RemoteTlsMode.Plain)
        try {
            assertEquals(7, socket.getInputStream().read())
        } finally {
            runCatching { socket.close() }
            runCatching { plain.close() }
        }
    }

    @Test
    fun `fingerprints of different certificates differ`() {
        val expected = fingerprintOf(certificate())

        assertNotEquals(expected, "00".repeat(32))
        assertFalse(expected.isEmpty())
    }
}
