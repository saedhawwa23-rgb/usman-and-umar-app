package com.opendroid.ai.remote.socks

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SOCKS5 (RFC 1928) with username/password authentication (RFC 1929).
 *
 * This is the transport for internet sharing: the phone opens a CONNECT per destination and the
 * desktop makes the outbound connection, so the phone's traffic leaves through the desktop's
 * network. The same code runs on both sides, so the wire format cannot drift.
 *
 * RFC 1929 carries a static password, which would be replayable on its own. The password here is
 * instead a short-lived HMAC over the paired secret, a timestamp and a nonce: the desktop can
 * verify it without storing anything, it expires, and a captured credential cannot be reused.
 */
object Socks5 {

    const val VERSION: Byte = 0x05

    const val AUTH_NONE: Byte = 0x00
    const val AUTH_USERPASS: Byte = 0x02
    const val AUTH_UNACCEPTABLE: Byte = 0xFF.toByte()

    const val USERPASS_VERSION: Byte = 0x01
    const val AUTH_SUCCESS: Byte = 0x00
    const val AUTH_FAILURE: Byte = 0x01

    const val CMD_CONNECT: Byte = 0x01

    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    const val REP_SUCCESS: Byte = 0x00
    const val REP_GENERAL_FAILURE: Byte = 0x01
    const val REP_NOT_ALLOWED: Byte = 0x02
    const val REP_HOST_UNREACHABLE: Byte = 0x04
    const val REP_COMMAND_UNSUPPORTED: Byte = 0x07
    const val REP_ATYP_UNSUPPORTED: Byte = 0x08

    /** Default port the desktop agent listens on for proxy requests. */
    const val DEFAULT_PORT = 45872

    /** How long an issued credential stays valid. Short, because it is minted per connection. */
    const val CREDENTIAL_TTL_MS = 60_000L

    /** Domains longer than this cannot be encoded in the RFC 1928 length-prefixed form. */
    const val MAX_DOMAIN_BYTES = 255

    private const val CREDENTIAL_LABEL = "opendroid-socks5-v1"

    /**
     * An address as it appears on the wire.
     *
     * Deliberately unvalidated: a reply's bound address is `0.0.0.0:0` when the proxy has nothing
     * meaningful to report, and RFC 1928 allows that. Use [Target] for something to connect to.
     */
    data class Address(val host: String, val port: Int)

    /** A destination: a host name or a literal address, plus a port that must be dialable. */
    data class Target(val host: String, val port: Int) {
        init {
            require(host.isNotBlank()) { "Target host is required" }
            require(port in 1..65535) { "Target port must be 1..65535, was $port" }
        }

        fun toAddress() = Address(host, port)

        companion object {
            /** Promotes a wire address to a target, rejecting a port that cannot be dialled. */
            fun from(address: Address): Target = Target(address.host, address.port)
        }
    }

    // ---- Address codec ----

    /**
     * Writes [address] in SOCKS5 address form.
     *
     * A name that is not a literal IP is sent as ATYP_DOMAIN so the desktop resolves it. That
     * matters: resolution then happens on the desktop's network, which is the whole point of
     * sharing its internet, and it avoids depending on the phone's resolver.
     */
    fun writeAddress(out: DataOutputStream, address: Address) {
        val literal = parseLiteral(address.host)
        when {
            literal != null && literal.size == 4 -> {
                out.writeByte(ATYP_IPV4.toInt())
                out.write(literal)
            }
            literal != null && literal.size == 16 -> {
                out.writeByte(ATYP_IPV6.toInt())
                out.write(literal)
            }
            else -> {
                val name = address.host.toByteArray(Charsets.UTF_8)
                require(name.size <= MAX_DOMAIN_BYTES) {
                    "Host name is ${name.size} bytes, over the SOCKS5 limit of $MAX_DOMAIN_BYTES"
                }
                out.writeByte(ATYP_DOMAIN.toInt())
                out.writeByte(name.size)
                out.write(name)
            }
        }
        out.writeShort(address.port)
    }

    /** Reads a SOCKS5 address, returning the host as text and the port. */
    fun readAddress(input: DataInputStream): Address {
        val atyp = input.readByte()
        val host = when (atyp) {
            ATYP_IPV4 -> ByteArray(4).also(input::readFully).let(::formatIpv4)
            ATYP_IPV6 -> ByteArray(16).also(input::readFully).let(::formatIpv6)
            ATYP_DOMAIN -> {
                val length = input.readUnsignedByte()
                if (length == 0) throw IOException("SOCKS5 domain address was empty")
                ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
            }
            else -> throw IOException("Unsupported SOCKS5 address type 0x%02x".format(atyp))
        }
        val port = input.readUnsignedShort()
        return Address(host, port)
    }

    private fun formatIpv4(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /**
     * RFC 5952 canonical IPv6 text: lowercase, no leading zeros, and the longest run of zero
     * groups collapsed to `::`. [InetAddress.hostAddress] does not compress, so `::1` would come
     * back as `0:0:0:0:0:0:0:1` and fail to compare equal to what the caller wrote.
     */
    private fun formatIpv6(bytes: ByteArray): String {
        val groups = IntArray(8) { i ->
            ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
        }
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        for (i in 0..8) {
            if (i < 8 && groups[i] == 0) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                val length = i - runStart
                if (length > bestLength) {
                    bestLength = length
                    bestStart = runStart
                }
                runStart = -1
            }
        }
        // A single zero group is written as "0", not "::", per RFC 5952 section 4.2.2.
        if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
        val head = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
        val tail = (bestStart + bestLength until 8).joinToString(":") { groups[it].toString(16) }
        return "$head::$tail"
    }

    /** Returns the 4 or 16 raw bytes when [host] is already a literal IP, otherwise null. */
    private fun parseLiteral(host: String): ByteArray? {
        val trimmed = host.trim().removeSurrounding("[", "]")
        val looksV4 = trimmed.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
        val looksV6 = trimmed.contains(':')
        if (!looksV4 && !looksV6) return null
        return runCatching { InetAddress.getByName(trimmed).address }.getOrNull()
    }

    // ---- Credentials ----

    private val random = SecureRandom()

    /**
     * A credential the phone sends as SOCKS5 username/password.
     *
     * [username] is the paired device id, so the desktop knows which secret to verify against.
     */
    data class Credential(val username: String, val password: String)

    /** Mints a credential for [deviceId] from the paired [secret], valid until the TTL expires. */
    fun issueCredential(secret: ByteArray, deviceId: String, nowMs: Long = System.currentTimeMillis()): Credential {
        require(secret.isNotEmpty()) { "A paired secret is required to mint a proxy credential" }
        require(deviceId.isNotBlank()) { "A device id is required to mint a proxy credential" }
        val nonce = ByteArray(16).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val mac = credentialMac(secret, deviceId, nowMs, nonce)
        return Credential(deviceId, "$nowMs.$nonce.$mac")
    }

    /**
     * Checks a credential against the paired [secret].
     *
     * Rejecting a stale timestamp bounds replay: a captured credential is useless after
     * [CREDENTIAL_TTL_MS]. [seenNonces] additionally refuses a nonce already used inside the
     * window, which closes replay within the window itself.
     */
    fun verifyCredential(
        secret: ByteArray,
        expectedDeviceId: String,
        password: String,
        nowMs: Long = System.currentTimeMillis(),
        seenNonces: MutableSet<String>? = null,
    ): Boolean {
        val parts = password.split('.')
        if (parts.size != 3) return false
        val issuedAt = parts[0].toLongOrNull() ?: return false
        val nonce = parts[1]
        val presentedMac = parts[2]
        if (nonce.isEmpty() || presentedMac.isEmpty()) return false
        // A clock far in the future is as suspicious as an expired one.
        if (kotlin.math.abs(nowMs - issuedAt) > CREDENTIAL_TTL_MS) return false
        val expectedMac = credentialMac(secret, expectedDeviceId, issuedAt, nonce)
        val matches = MessageDigest.isEqual(
            presentedMac.toByteArray(Charsets.UTF_8),
            expectedMac.toByteArray(Charsets.UTF_8),
        )
        if (!matches) return false
        if (seenNonces != null && !seenNonces.add(nonce)) return false
        return true
    }

    private fun credentialMac(secret: ByteArray, deviceId: String, issuedAtMs: Long, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(CREDENTIAL_LABEL.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(deviceId.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(issuedAtMs.toString().toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(nonce.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
    }
}
