package com.opendroid.ai.remote.gateway

/**
 * X25519 scalar multiplication, per RFC 7748.
 *
 * WireGuard keys are Curve25519 public keys, so the desktop agent needs to derive a public key from
 * a private one. The Android side uses the WireGuard library's own implementation; the JVM agent
 * has no such library and cannot use `java.security` XDH for this (the JDK exposes no way to derive
 * the public half from a raw private scalar), so the operation is implemented here and pinned to the
 * RFC 7748 test vectors.
 */
object X25519 {

    private const val P = "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed"

    /** Clamp a 32-byte private scalar as RFC 7748 requires. */
    fun clamp(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes, got ${privateKey.size}" }
        val k = privateKey.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    /** Multiply the base point by [privateKey], returning the 32-byte public key. */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray =
        scalarMultiply(clamp(privateKey), BASE_POINT)

    /**
     * Derive a public key from a base64 private key, the same encoding WireGuard uses.
     */
    fun publicKeyFromPrivateBase64(privateKeyBase64: String): String {
        val raw = java.util.Base64.getDecoder().decode(privateKeyBase64)
        require(raw.size == 32) { "WireGuard private key must decode to 32 bytes" }
        return java.util.Base64.getEncoder().encodeToString(publicKeyFromPrivate(raw))
    }

    /**
     * Montgomery ladder scalar multiplication on Curve25519.
     *
     * Written with BigInteger for clarity; this runs once at agent startup, never per packet, so
     * constant-time field arithmetic is not a concern here.
     */
    fun scalarMultiply(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32) { "scalar must be 32 bytes" }
        require(point.size == 32) { "u-coordinate must be 32 bytes" }

        val p = java.math.BigInteger(P, 16)
        val a24 = java.math.BigInteger("121665")

        // RFC 7748 masks the top bit of the u-coordinate before use.
        val maskedPoint = point.copyOf()
        maskedPoint[31] = (maskedPoint[31].toInt() and 127).toByte()
        var u = decodeLittleEndian(maskedPoint).mod(p)

        // X25519 always clamps the scalar, whether it came from a key file or a test vector.
        val k = decodeLittleEndian(clamp(scalar))
        val bits = 254

        var x1 = u
        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = u
        var z3 = java.math.BigInteger.ONE
        var swap = 0

        for (t in bits downTo 0) {
            val kt = k.shiftRight(t).and(java.math.BigInteger.ONE).toInt()
            swap = swap xor kt
            if (swap == 1) {
                val tmpX = x2; x2 = x3; x3 = tmpX
                val tmpZ = z2; z2 = z3; z3 = tmpZ
            }
            swap = kt

            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            val sum = da.add(cb).mod(p)
            val diff = da.subtract(cb).mod(p)
            x3 = sum.multiply(sum).mod(p)
            z3 = x1.multiply(diff.multiply(diff).mod(p)).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(a24.multiply(e)).mod(p)).mod(p)
        }

        if (swap == 1) {
            x2 = x3
            z2 = z3
        }

        val result = x2.multiply(z2.modPow(p.subtract(java.math.BigInteger.TWO), p)).mod(p)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(bytes: ByteArray): java.math.BigInteger {
        val le = bytes.copyOf()
        le.reverse()
        return java.math.BigInteger(1, le)
    }

    private fun encodeLittleEndian(value: java.math.BigInteger): ByteArray {
        // BigInteger.toByteArray is big-endian and may carry a leading sign byte; the caller only
        // ever passes a value below p, so the low 32 bytes are the whole number.
        val be = value.toByteArray()
        val out = ByteArray(32)
        val copyLen = minOf(be.size, 32)
        for (i in 0 until copyLen) {
            out[i] = be[be.size - 1 - i]
        }
        return out
    }

    /** The Curve25519 base point u=9. */
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }
}
