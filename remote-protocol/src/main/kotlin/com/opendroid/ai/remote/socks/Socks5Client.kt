package com.opendroid.ai.remote.socks

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone side of internet sharing: opens a SOCKS5 CONNECT through the desktop.
 *
 * Every destination is a fresh CONNECT, which is what a proxy is expected to do. The credential is
 * minted per connection from the paired secret, so nothing long-lived is written to disk and a
 * captured handshake cannot be replayed.
 */
class Socks5Client(
    private val host: String,
    private val port: Int = Socks5.DEFAULT_PORT,
    private val secret: ByteArray,
    private val deviceId: String,
    private val connectTimeoutMs: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Opens a tunnel to [target] and returns the live socket, ready for application bytes. */
    fun connect(target: Socks5.Target): Socket {
        require(secret.isNotEmpty()) { "Internet sharing needs a paired desktop secret" }
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Greeting: SOCKS5, offering only username/password. Never AUTH_NONE.
            output.writeByte(Socks5.VERSION.toInt())
            output.writeByte(1)
            output.writeByte(Socks5.AUTH_USERPASS.toInt())
            output.flush()

            val version = input.readByte()
            if (version != Socks5.VERSION) {
                throw IOException("The desktop proxy answered with SOCKS version $version, expected 5")
            }
            val method = input.readByte()
            if (method != Socks5.AUTH_USERPASS) {
                throw IOException(
                    "The desktop proxy refused username/password authentication. " +
                        "Check that the desktop agent is running with internet sharing enabled."
                )
            }

            val credential = Socks5.issueCredential(secret, deviceId, clock())
            val user = credential.username.toByteArray(Charsets.UTF_8)
            val pass = credential.password.toByteArray(Charsets.UTF_8)
            output.writeByte(Socks5.USERPASS_VERSION.toInt())
            output.writeByte(user.size)
            output.write(user)
            output.writeByte(pass.size)
            output.write(pass)
            output.flush()

            input.readByte() // auth sub-version
            if (input.readByte() != Socks5.AUTH_SUCCESS) {
                throw IOException(
                    "The desktop rejected this phone's proxy credential. " +
                        "Re-pair with the desktop; the pairing may have been revoked."
                )
            }

            output.writeByte(Socks5.VERSION.toInt())
            output.writeByte(Socks5.CMD_CONNECT.toInt())
            output.writeByte(0)
            Socks5.writeAddress(output, target.toAddress())
            output.flush()

            input.readByte() // reply version
            val reply = input.readByte()
            input.readByte() // reserved
            Socks5.readAddress(input) // bound address, unused: it may legitimately be 0.0.0.0:0
            if (reply != Socks5.REP_SUCCESS) {
                throw IOException("The desktop could not reach ${target.host}:${target.port} (${describe(reply)})")
            }
            // Application data is not request/response, so the timeout must not leak into it.
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun describe(code: Byte): String = when (code) {
        Socks5.REP_GENERAL_FAILURE -> "general failure"
        Socks5.REP_NOT_ALLOWED -> "refused by the desktop's policy"
        Socks5.REP_HOST_UNREACHABLE -> "host unreachable"
        Socks5.REP_COMMAND_UNSUPPORTED -> "command unsupported"
        Socks5.REP_ATYP_UNSUPPORTED -> "address type unsupported"
        else -> "SOCKS reply 0x%02x".format(code)
    }
}
