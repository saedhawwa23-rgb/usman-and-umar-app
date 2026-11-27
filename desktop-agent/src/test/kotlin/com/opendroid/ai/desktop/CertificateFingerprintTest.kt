package com.opendroid.ai.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/**
 * The phone pins the fingerprint the agent prints. If the two sides computed it differently the
 * secure path would fail closed on every connection, so the agreement is asserted here against a
 * real keystore.
 */
class CertificateFingerprintTest {

    private fun keystore(): Pair<File, String> {
        val dir = Files.createTempDirectory("agent-fingerprint-test").toFile()
        val file = File(dir, "agent.p12")
        val process = ProcessBuilder(
            "keytool", "-genkeypair", "-alias", "agent",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=opendroid-agent", "-storetype", "PKCS12",
            "-keystore", file.absolutePath, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertTrue("keytool must succeed: $output", process.exitValue() == 0)
        return file to "changeit"
    }

    @Test
    fun `the printed fingerprint is the sha256 of the certificate, colon separated`() {
        val (file, password) = keystore()

        val printed = certificateFingerprint(TlsOptions(file.absolutePath, password))

        // keytool's own format: 32 uppercase hex pairs joined by colons.
        val parts = printed.split(":")
        assertEquals(32, parts.size)
        assertTrue(parts.all { it.length == 2 && it.all { c -> c.isDigit() || c in 'A'..'F' } })

        // And it must equal an independent computation over the same certificate.
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password.toCharArray()) }
        val cert = store.getCertificate("agent") as java.security.cert.X509Certificate
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        assertEquals(expected, printed)
    }
}
