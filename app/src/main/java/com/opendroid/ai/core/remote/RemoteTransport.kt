package com.opendroid.ai.core.remote

import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * How the phone reaches the desktop agent.
 *
 * The agent presents a self-signed certificate, so there is no CA to chain to. Pinning the
 * certificate's SHA-256 fingerprint is the check that actually matters: it proves the phone is
 * talking to the machine it paired with, and a different certificate on the path fails the
 * handshake before any credential is sent.
 */
sealed interface RemoteTlsMode {
    /** Plain TCP. Only acceptable on loopback or a trusted LAN. */
    data object Plain : RemoteTlsMode

    /**
     * TLS with the server certificate pinned to [fingerprintSha256], a lowercase or uppercase hex
     * string with colons optional (the format `keytool -printcert` prints).
     */
    data class Pinned(val fingerprintSha256: String) : RemoteTlsMode
}

/** Normalises a certificate fingerprint so `AA:BB` and `aabb` compare equal. */
fun normaliseFingerprint(value: String): String =
    value.filter { it.isLetterOrDigit() }.lowercase()

/** The SHA-256 fingerprint of a certificate, in the same normalised form. */
fun fingerprintOf(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }

/**
 * Opens the socket for a session.
 *
 * [mode] is resolved against the pairing record: a paired desktop carries the fingerprint it was
 * paired under, so the phone pins without the user copying anything. A first pairing passes the
 * fingerprint the user was shown on the desktop.
 */
object RemoteTransport {

    const val CONNECT_TIMEOUT_MS = 10_000

    fun open(host: String, port: Int, mode: RemoteTlsMode): Socket = when (mode) {
        is RemoteTlsMode.Plain -> Socket().apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        is RemoteTlsMode.Pinned -> openPinned(host, port, mode.fingerprintSha256)
    }

    private fun openPinned(host: String, port: Int, expectedFingerprint: String): SSLSocket {
        val expected = normaliseFingerprint(expectedFingerprint)
        require(expected.isNotEmpty()) { "A pinned connection needs the desktop's certificate fingerprint" }

        val trustManager = PinningTrustManager(expected)
        val context = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf(trustManager), null)
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // The agent's certificate carries CN=opendroid-agent, not the host name, and the
        // fingerprint pin is the identity check here. Hostname verification would reject every
        // address (a LAN IP, a DDNS name) the user legitimately reaches the desktop on.
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
        socket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        socket.startHandshake()
        return socket
    }

    /**
     * Accepts exactly one certificate: the pinned fingerprint. Everything else is refused.
     *
     * Lint flags any custom X509TrustManager because most implementations skip validation.
     * This one is stricter than the platform default: it compares the leaf against the
     * fingerprint recorded at pairing time and has no path that returns without checking.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinningTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("This side is a TLS client; it does not accept client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("The desktop presented no certificate")
            val actual = fingerprintOf(leaf)
            if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                throw CertificateException(
                    "The desktop's certificate does not match the paired fingerprint. " +
                        "Expected $expected but received $actual. The pairing may be stale, or " +
                        "something is intercepting the connection."
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
