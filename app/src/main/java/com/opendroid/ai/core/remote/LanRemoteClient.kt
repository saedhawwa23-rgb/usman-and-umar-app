package com.opendroid.ai.core.remote

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends a signed request to a paired device over the owner's LAN transport.
 *
 * This is the controlling side. It speaks the same one-line-in, one-line-out protocol as
 * [LanRemoteControlServer]: it opens a socket to a host the owner typed, writes one signed
 * request line, and reads one reply line. It does not discover devices, does not use a relay,
 * and never sends anything that is not already signed by [RemoteControlService.sign].
 */
class LanRemoteClient(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /** @param host the listener's address, e.g. `192.168.1.20`; [port] defaults to the app's. */
    fun send(
        host: String,
        port: Int = LanRemoteControlServer.DEFAULT_PORT,
        request: SignedRemoteRequest,
    ): Result<RemoteTransportReply> = runCatching {
        val address = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
        require(address.isNotBlank()) { "Enter the address of the paired device, for example 192.168.1.20." }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(RemoteRequestCodec.encode(request))
            writer.write("\n")
            writer.flush()
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                ?: error("The remote device closed the connection without answering.")
            RemoteTransportReply.decode(line)
                ?: error("The remote device answered with a response this app could not read.")
        }
    }
}
