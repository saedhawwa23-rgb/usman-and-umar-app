package com.opendroid.ai.core.drive

import com.opendroid.ai.core.security.EncryptedSecret
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretAeadCipher
import com.opendroid.ai.core.security.SecretRecordStorage
import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleDriveAccountPoolTest {
    @Test
    fun `adding and switching accounts preserves a separate encrypted token for each email`() {
        val storage = InMemorySecretStorage()
        val vault = GoogleDriveTokenVaultImpl(KeystoreSecretRecords(storage, TestAeadCipher()))

        vault.save("First.User@example.com", "access-token-one")
        vault.save("second.user@example.com", "access-token-two")

        assertEquals("access-token-one", vault.read("first.user@example.com"))
        assertEquals("access-token-two", vault.read("SECOND.USER@example.com"))
        assertEquals(2, storage.records.size)
        assertFalse(storage.records.values.any { it.contains("access-token-one") || it.contains("access-token-two") })

        vault.save("second.user@example.com", "second-account-refreshed-token")
        assertEquals("access-token-one", vault.read("first.user@example.com"))
        assertEquals("second-account-refreshed-token", vault.read("second.user@example.com"))

        vault.remove("second.user@example.com")
        assertNull(vault.read("second.user@example.com"))
        assertEquals("access-token-one", vault.read("first.user@example.com"))
    }

    @Test
    fun `cached access token is not reused after its safe refresh window`() {
        val vault = GoogleDriveTokenVaultImpl(
            KeystoreSecretRecords(InMemorySecretStorage(), TestAeadCipher())
        )
        vault.save("cached@example.com", "short-lived-access-token")

        assertEquals("short-lived-access-token", vault.readValid("cached@example.com", System.currentTimeMillis()))
        assertNull(vault.readValid("cached@example.com", System.currentTimeMillis() + 51L * 60L * 1000L))
    }

    @Test
    fun `pool size comes from account API quota snapshots rather than a fixed 15 GB value`() {
        val oneAccount = listOf(account("one@example.com", limit = 12L * GIB, usage = 2L * GIB))
        val oneSummary = summarizeGoogleDrivePool(oneAccount)
        assertEquals(12L * GIB, oneSummary.totalLimitBytes)
        assertEquals(2L * GIB, oneSummary.totalUsageBytes)
        assertEquals("12.0 GB", formatGoogleStorage(oneSummary.totalLimitBytes!!))

        val nineAccounts = (1..9).map { index ->
            account("user$index@example.com", limit = 15L * GIB, usage = GIB / 3L)
        }
        val nineSummary = summarizeGoogleDrivePool(nineAccounts)
        assertEquals(9, nineSummary.accountCount)
        assertEquals(9, nineSummary.verifiedAccountCount)
        assertEquals(135L * GIB, nineSummary.totalLimitBytes)
        assertEquals("135.0 GB", formatGoogleStorage(nineSummary.totalLimitBytes!!))
    }

    @Test
    fun `incomplete quota checks do not present a partial sum as the total pool`() {
        val accounts = listOf(
            account("checked@example.com", limit = 15L * GIB, usage = GIB),
            GoogleDriveAccountEntity(
                id = "pending@example.com",
                email = "pending@example.com",
                displayName = "pending",
                connectedAtMillis = 2L,
            ),
        )
        val summary = summarizeGoogleDrivePool(accounts)
        assertEquals(2, summary.accountCount)
        assertEquals(1, summary.verifiedAccountCount)
        assertEquals(null, summary.totalLimitBytes)
        assertEquals(null, summary.totalUsageBytes)
        assertFalse(summary.isComplete)
    }

    @Test
    fun `upload candidates are ordered by live free storage with unlimited accounts first`() {
        val candidates = listOf(
            candidate("low@example.com", limit = 15L * GIB, usage = 8L * GIB),
            candidate("high@example.com", limit = 15L * GIB, usage = 2L * GIB),
            candidate("unlimited@example.com", limit = null, usage = 40L * GIB),
        )

        assertEquals(
            listOf("unlimited@example.com", "high@example.com", "low@example.com"),
            orderDriveCandidates(candidates).map { it.account.email },
        )
    }

    private fun account(email: String, limit: Long, usage: Long) = GoogleDriveAccountEntity(
        id = email,
        email = email,
        displayName = email.substringBefore('@'),
        connectedAtMillis = 1L,
        quotaLimitBytes = limit,
        quotaUsageBytes = usage,
        quotaUpdatedAtMillis = 2L,
    )

    private fun candidate(email: String, limit: Long?, usage: Long) = DriveUploadCandidate(
        account = GoogleDriveAccountEntity(
            id = email,
            email = email,
            displayName = email.substringBefore('@'),
            connectedAtMillis = 1L,
        ),
        accessToken = "test-token",
        quota = DriveStorageQuota(limitBytes = limit, usageBytes = usage),
    )

    private class InMemorySecretStorage : SecretRecordStorage {
        val records = linkedMapOf<String, String>()
        override fun read(key: String): String? = records[key]
        override fun write(key: String, value: String): Boolean { records[key] = value; return true }
        override fun remove(key: String): Boolean { records.remove(key); return true }
        override fun keys(): Set<String> = records.keys.toSet()
    }

    private class TestAeadCipher : SecretAeadCipher {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return EncryptedSecret(iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        override fun resetForReentry() {
            throw GeneralSecurityException("Not used by this test.")
        }
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
