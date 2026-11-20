package com.opendroid.ai.core.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * RFC 7636 Appendix B publishes a verifier and its S256 challenge. Reproducing that pair exactly
 * is the only way to prove the challenge is real SHA-256 and not a plausible-looking stand-in.
 */
class PkceTest {

    // RFC 7636 Appendix B.
    private val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val rfcChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    fun `s256 challenge matches the published RFC 7636 appendix B vector`() {
        assertEquals(rfcChallenge, Pkce.challengeS256(rfcVerifier))
    }

    @Test
    fun `generated verifier is 43 characters of the unreserved set`() {
        val verifier = Pkce.generateVerifier()

        assertEquals(43, verifier.length)
        assertTrue(Pkce.isValidVerifier(verifier))
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun `generated verifier has no padding characters`() {
        repeat(50) { assertFalse(Pkce.generateVerifier().contains('=')) }
    }

    @Test
    fun `two verifiers differ`() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
    }

    @Test
    fun `challenge is 43 characters and never equals the verifier`() {
        val verifier = Pkce.generateVerifier()

        val challenge = Pkce.challengeS256(verifier)

        assertEquals(43, challenge.length)
        assertNotEquals(verifier, challenge)
        assertTrue(Pkce.isValidChallenge(challenge))
    }

    @Test
    fun `a verifier shorter than 43 characters is rejected`() {
        assertFalse(Pkce.isValidVerifier("too-short"))
        val error = runCatching { Pkce.challengeS256("too-short") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a verifier with a reserved character is rejected`() {
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + "/"))
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + "="))
    }

    @Test
    fun `a 128 character verifier is accepted and a 129 character one is not`() {
        assertTrue(Pkce.isValidVerifier("a".repeat(128)))
        assertFalse(Pkce.isValidVerifier("a".repeat(129)))
    }

    @Test
    fun `constant time comparison is true only for equal strings`() {
        assertTrue(Pkce.constantTimeEquals("abc123", "abc123"))
        assertFalse(Pkce.constantTimeEquals("abc123", "abc124"))
        assertFalse(Pkce.constantTimeEquals("abc123", "abc1234"))
    }

    @Test
    fun `transaction carries the state, verifier, challenge and redirect`() {
        val tx = PkceTransaction.create("com.example.app:/oauth2redirect", SecureRandom(), nowMs = 1_000L)

        assertEquals("com.example.app:/oauth2redirect", tx.redirectUri)
        assertEquals(PkceMethod.S256, tx.method)
        assertEquals(tx.challenge, Pkce.challengeS256(tx.verifier))
        assertTrue(tx.state.isNotBlank())
    }

    @Test
    fun `transaction expires after its ttl`() {
        val tx = PkceTransaction.create("https://app/cb", SecureRandom(), nowMs = 0L)

        assertFalse(tx.isExpired(nowMs = PkceTransaction.DEFAULT_TTL_MS))
        assertTrue(tx.isExpired(nowMs = PkceTransaction.DEFAULT_TTL_MS + 1))
    }

    @Test
    fun `token set redacts both tokens in its string form`() {
        val tokens = OAuthTokenSet(
            accessToken = "super-secret-access",
            refreshToken = "super-secret-refresh",
            expiresAtMs = 123L,
        )

        val rendered = tokens.toString()

        assertFalse(rendered.contains("super-secret-access"))
        assertFalse(rendered.contains("super-secret-refresh"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `token set reports expiry with a safety skew`() {
        val tokens = OAuthTokenSet(accessToken = "a", expiresAtMs = 100_000L)

        assertFalse(tokens.isExpired(nowMs = 0L))
        assertTrue(tokens.isExpired(nowMs = 100_000L))
        // A token expiring within the 60s skew is treated as expired, so a request is not sent
        // with a token that dies in flight.
        assertTrue(tokens.isExpired(nowMs = 50_000L))
    }

    @Test
    fun `a token with no expiry never reports expired`() {
        assertFalse(OAuthTokenSet(accessToken = "a", expiresAtMs = 0L).isExpired(nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `authorization url carries pkce, state and scope`() {
        val client = OAuthAuthorizationClient()
        val provider = OAuthProvider(
            id = "example",
            displayName = "Example",
            authorizationEndpoint = "https://example.com/authorize",
            tokenEndpoint = "https://example.com/token",
            redirectUri = "com.opendroid.ai:/oauth2redirect",
            clientId = "public-client",
            scopes = listOf("read", "write"),
        )
        val tx = PkceTransaction.create(provider.redirectUri, SecureRandom(), nowMs = 0L)

        val url = client.authorizationUrl(provider, tx)

        assertTrue(url.startsWith("https://example.com/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=${tx.challenge}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${tx.state}"))
        assertTrue(url.contains("scope=read+write"))
    }

    @Test
    fun `a non-https authorization endpoint is refused`() {
        val client = OAuthAuthorizationClient()
        val provider = OAuthProvider(
            id = "x", displayName = "X",
            authorizationEndpoint = "http://example.com/authorize",
            tokenEndpoint = "https://example.com/token",
            redirectUri = "https://app/cb", clientId = "c", scopes = emptyList(),
        )
        val error = runCatching { client.begin(provider) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a mismatched state is rejected`() {
        val client = OAuthAuthorizationClient()
        val tx = PkceTransaction.create("https://app/cb", SecureRandom(), nowMs = 0L)

        val result = client.parseRedirect(tx, "https://app/cb?code=abc&state=not-the-right-state")

        assertTrue(result is OAuthResult.Failure)
        assertTrue((result as OAuthResult.Failure).error is OAuthError.Redirect)
    }

    @Test
    fun `a provider error is surfaced, not treated as success`() {
        val client = OAuthAuthorizationClient()
        val tx = PkceTransaction.create("https://app/cb", SecureRandom(), nowMs = 0L)

        val result = client.parseRedirect(
            tx,
            "https://app/cb?error=access_denied&error_description=user+cancelled&state=${tx.state}",
        )

        assertTrue(result is OAuthResult.Failure)
        assertTrue((result as OAuthResult.Failure).error.let { it as OAuthError.Redirect }.message.contains("user cancelled"))
    }

    @Test
    fun `token exchange form includes the verifier for pkce`() {
        val client = OAuthAuthorizationClient()
        val provider = OAuthProvider(
            id = "x", displayName = "X",
            authorizationEndpoint = "https://example.com/authorize",
            tokenEndpoint = "https://example.com/token",
            redirectUri = "https://app/cb", clientId = "c", scopes = emptyList(),
        )
        val tx = PkceTransaction.create(provider.redirectUri, SecureRandom(), nowMs = 0L)

        val form = client.tokenExchangeForm(provider, tx, "auth-code")

        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=auth-code"))
        assertTrue(form.contains("code_verifier=${tx.verifier}"))
    }

    @Test
    fun `a token endpoint error status is a failure with the provider code`() {
        val result = OAuthTokenParser.parse(
            """{"error":"invalid_grant","error_description":"code expired"}""",
            httpStatus = 400, nowMs = 0L,
        )

        assertTrue(result is OAuthResult.Failure)
        val error = (result as OAuthResult.Failure).error as OAuthError.Exchange
        assertEquals(400, error.httpStatus)
        assertTrue(error.message.contains("invalid_grant"))
    }

    @Test
    fun `a successful token response converts expires_in to an absolute expiry`() {
        val result = OAuthTokenParser.parse(
            """{"access_token":"at","refresh_token":"rt","token_type":"Bearer","expires_in":3600}""",
            httpStatus = 200, nowMs = 1_000L,
        )

        assertTrue(result is OAuthResult.Success)
        val tokens = (result as OAuthResult.Success).tokens
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(1_000L + 3_600_000L, tokens.expiresAtMs)
    }

    @Test
    fun `a refresh response without a new refresh token keeps the previous one`() {
        val result = OAuthTokenParser.parse(
            """{"access_token":"at2","expires_in":3600}""",
            httpStatus = 200, nowMs = 0L, previousRefreshToken = "rt-old",
        )

        assertEquals("rt-old", (result as OAuthResult.Success).tokens.refreshToken)
    }

    @Test
    fun `a token response with no access token is a failure`() {
        val result = OAuthTokenParser.parse("""{"token_type":"Bearer"}""", httpStatus = 200, nowMs = 0L)

        assertTrue(result is OAuthResult.Failure)
    }

    @Test
    fun `token vault round-trips a token set through encrypted storage`() {
        val store = InMemorySecretBytesStore()
        val vault = OAuthTokenVault(store)
        val tokens = OAuthTokenSet("access-1", "refresh-1", "Bearer", "read write", 5_000L)

        vault.save("example", tokens)

        assertEquals(tokens, vault.load("example"))
        assertEquals(setOf("example"), vault.connectedProviderIds())
    }

    @Test
    fun `token vault returns null for an unknown provider`() {
        assertNull(OAuthTokenVault(InMemorySecretBytesStore()).load("never-connected"))
    }

    @Test
    fun `token vault remove clears the provider`() {
        val vault = OAuthTokenVault(InMemorySecretBytesStore())
        vault.save("example", OAuthTokenSet("a"))
        vault.remove("example")
        assertNull(vault.load("example"))
        assertTrue(vault.connectedProviderIds().isEmpty())
    }

    @Test
    fun `token vault never writes the raw token into the storage key`() {
        val store = InMemorySecretBytesStore()
        OAuthTokenVault(store).save("example", OAuthTokenSet("secret-access-token"))
        assertTrue(store.keys().none { it.contains("secret-access-token") })
    }
}

/** Minimal in-memory store; the Android build binds the Keystore-backed implementation. */
private class InMemorySecretBytesStore : com.opendroid.ai.core.security.SecretBytesStore {
    private val map = LinkedHashMap<String, ByteArray>()
    override fun read(key: String): ByteArray? = map[key]?.copyOf()
    override fun write(key: String, value: ByteArray) { map[key] = value.copyOf() }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}
