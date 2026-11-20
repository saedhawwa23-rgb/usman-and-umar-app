package com.opendroid.ai.desktop

import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Entry point for the desktop companion agent.
 *
 * Usage:
 *   java -jar opendroid-desktop-agent-all.jar --port 8777 --store ./agent-clients.json \
 *        --root ~/Shared [--tls-keystore agent.p12 --tls-password ...] [--no-input] [--no-files]
 *
 * With no keystore the agent listens on plain TCP, which is only appropriate on a trusted local
 * network. Supplying a PKCS#12 keystore turns on TLS, which is required for any remote use.
 */
fun main(args: Array<String>) {
    val options = AgentOptions.parse(args)
    if (options.showHelp) {
        println(AgentOptions.USAGE)
        return
    }

    val store = ClientStore(File(options.storePath))
    val pairing = PairingController()
    val controller = DesktopController()
    val config = AgentConfig(
        serverName = options.serverName,
        port = options.port,
        fileRoot = File(options.fileRoot).canonicalFile,
        allowFileTransfer = !options.noFiles,
        allowInput = !options.noInput,
    )

    val serverSocket = options.tls?.let { createTlsServer(it, options.port) }
        ?: ServerSocket(options.port)

    println("opendroid desktop agent")
    println("  name        : ${options.serverName}")
    println("  listening   : ${options.port} (${if (options.tls != null) "TLS" else "plain TCP"})")
    println("  shared root : ${config.fileRoot}")
    println("  input       : ${if (config.allowInput) "enabled" else "disabled"}")
    println("  file access : ${if (config.allowFileTransfer) "enabled" else "disabled"}")
    if (options.tls == null) {
        println("  WARNING: no TLS keystore supplied. Use this only on a trusted local network.")
    }

    val code = pairing.open()
    println()
    println("  Pairing code: $code   (valid for 5 minutes)")
    println("  Enter this code on the phone to pair. Press Ctrl-C to stop.")
    println()

    val pool = Executors.newFixedThreadPool(config.maxClients)
    val shutdown = Thread {
        println("shutting down")
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }
    Runtime.getRuntime().addShutdownHook(shutdown)

    while (!serverSocket.isClosed) {
        val socket = try {
            serverSocket.accept()
        } catch (e: Exception) {
            break
        }
        pool.submit {
            socket.use { s ->
                println("connection from ${s.inetAddress.hostAddress}")
                val session = DesktopSession(config, store, controller, pairing) { println("  $it") }
                val outcome = session.handle(s.getInputStream(), s.getOutputStream())
                println("session ended: ${outcome.reason}")
            }
        }
    }
}

private fun createTlsServer(tls: TlsOptions, port: Int): SSLServerSocket {
    val keyStore = KeyStore.getInstance("PKCS12")
    FileInputStream(tls.keystorePath).use { keyStore.load(it, tls.password.toCharArray()) }
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, tls.password.toCharArray())
    val context = SSLContext.getInstance("TLSv1.3")
    context.init(factory.keyManagers, null, null)
    // Only modern protocol versions and no client-optional renegotiation.
    return (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
        enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        needClientAuth = false
    }
}

data class TlsOptions(val keystorePath: String, val password: String)

data class AgentOptions(
    val port: Int,
    val storePath: String,
    val fileRoot: String,
    val serverName: String,
    val tls: TlsOptions?,
    val noInput: Boolean,
    val noFiles: Boolean,
    val showHelp: Boolean,
) {
    companion object {
        val USAGE = """
        opendroid desktop agent

          --port <n>            port to listen on (default 8777)
          --store <path>        client store file (default ./agent-clients.json)
          --root <path>         folder shared for file transfer (default ./shared)
          --name <text>         name shown on the phone (default: host name)
          --tls-keystore <p12>  PKCS#12 keystore to enable TLS
          --tls-password <pw>   keystore password
          --no-input            do not accept mouse or keyboard input
          --no-files            do not allow file transfer
          --help                show this message
        """.trimIndent()

        fun parse(args: Array<String>): AgentOptions {
            var port = 8777
            var storePath = "agent-clients.json"
            var fileRoot = "shared"
            var serverName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop")
            var tls: TlsOptions? = null
            var noInput = false
            var noFiles = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
                    "--store" -> storePath = args.getOrNull(++i) ?: storePath
                    "--root" -> fileRoot = args.getOrNull(++i) ?: fileRoot
                    "--name" -> serverName = args.getOrNull(++i) ?: serverName
                    "--tls-keystore" -> {
                        val path = args.getOrNull(++i)
                        val password = args.getOrNull(++i) ?: ""
                        if (path != null) tls = TlsOptions(path, password)
                    }
                    "--no-input" -> noInput = true
                    "--no-files" -> noFiles = true
                    "--help", "-h" -> help = true
                    else -> if (arg.startsWith("--tls-password")) i++ // consumed with the keystore
                }
                i++
            }
            return AgentOptions(port, storePath, fileRoot, serverName, tls, noInput, noFiles, help)
        }
    }
}
