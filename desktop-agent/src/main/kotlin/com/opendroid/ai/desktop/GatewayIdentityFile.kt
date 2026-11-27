package com.opendroid.ai.desktop

import com.opendroid.ai.remote.gateway.DesktopGatewayConfig
import com.opendroid.ai.remote.gateway.X25519
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The desktop's WireGuard identity, kept beside the client store.
 *
 * Only the private key is persisted; the public key is derived on load so the two can never drift
 * apart. The file is restricted to the owner where the platform supports it.
 */
class GatewayIdentityFile(private val file: File) {

    data class Identity(val privateKeyBase64: String, val publicKeyBase64: String)

    /** Load the existing identity, creating one on first run. */
    fun loadOrCreate(): Identity {
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) {
            val publicKey = runCatching { X25519.publicKeyFromPrivateBase64(existing) }.getOrNull()
            if (publicKey != null) return Identity(existing, publicKey)
            // A corrupt key file would make every gateway start fail; regenerate instead of
            // leaving the owner with an unexplained error.
        }
        val privateKey = DesktopGatewayConfig.newPrivateKey()
        write(privateKey)
        return Identity(privateKey, X25519.publicKeyFromPrivateBase64(privateKey))
    }

    private fun write(privateKey: String) {
        file.parentFile?.mkdirs()
        file.writeText(privateKey)
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
