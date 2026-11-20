package com.opendroid.ai.core.oauth

import java.net.URLEncoder
import java.security.SecureRandom

/**
 * An OAuth 2.0 provider registration. [clientId] is public for a native app: PKCE means no client
 * secret is embedded, so nothing here is a secret except the per-attempt verifier.
 */
data class OAuthProvider(
    val id: String,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val clientId: String,
    val scopes: List<String>,
    /** Optional RFC 8707 resource indicator, so a token is audience-bound to one API. */
    val resource: String? = null,
    val usePkce: Boolean = true,
)

/** A stored token set. [accessToken] and [refreshToken] are never logged or shown. */
data class OAuthTokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val expiresAtMs: Long = 0L,
) {
    fun isExpired(nowMs: Long, skewMs: Long = 60_000L): Boolean =
        expiresAtMs > 0 && nowMs + skewMs >= expiresAtMs

    /** Bearer header value for the transport only; never returned to the model or a log. */
    fun authorizationHeader(): String = "$tokenType $accessToken"

    override fun toString(): String =
        "OAuthTokenSet(tokenType=$tokenType, scope=$scope, expiresAtMs=$expiresAtMs, accessToken=<redacted>, refreshToken=<redacted>)"
}

sealed interface OAuthError {
    data class Configuration(val message: String) : OAuthError
    data class Redirect(val message: String) : OAuthError
    data class Exchange(val message: String, val httpStatus: Int? = null) : OAuthError
}

sealed interface OAuthResult {
    data class Success(val tokens: OAuthTokenSet) : OAuthResult
    data class Failure(val error: OAuthError) : OAuthResult
}

/**
 * Builds the authorization request and validates the redirect. The token exchange itself is
 * performed by a transport so this class stays testable without a network, and so the client can
 * use the app's existing HTTP stack.
 */
class OAuthAuthorizationClient(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Start an attempt. The returned transaction holds the verifier and must be stored securely. */
    fun begin(provider: OAuthProvider): PkceTransaction {
        require(provider.authorizationEndpoint.startsWith("https://")) {
            "Authorization endpoint must use HTTPS"
        }
        require(provider.tokenEndpoint.startsWith("https://")) { "Token endpoint must use HTTPS" }
        require(provider.clientId.isNotBlank()) { "Client id is required" }
        return PkceTransaction.create(provider.redirectUri, random, clock())
    }

    /**
     * The URL to open in a browser or Custom Tab. `state` binds the response to this attempt and
     * `code_challenge` binds the code to the verifier, so a stolen code is useless without it.
     */
    fun authorizationUrl(provider: OAuthProvider, transaction: PkceTransaction): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to provider.clientId,
            "redirect_uri" to provider.redirectUri,
            "scope" to provider.scopes.joinToString(" "),
            "state" to transaction.state,
        )
        if (provider.usePkce) {
            params["code_challenge"] = transaction.challenge
            params["code_challenge_method"] = transaction.method.name
        }
        provider.resource?.let { params["resource"] = it }
        return provider.authorizationEndpoint + "?" + params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
    }

    /**
     * Validate the redirect. A mismatched or missing `state` is rejected, and an `error` returned
     * by the provider is surfaced verbatim instead of being treated as a success.
     */
    fun parseRedirect(transaction: PkceTransaction, redirectUrl: String): OAuthResult {
        val query = redirectUrl.substringAfter('?', "")
        if (query.isEmpty()) {
            return OAuthResult.Failure(OAuthError.Redirect("Redirect did not contain a query string"))
        }
        val params = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else decode(part.substring(0, i)) to decode(part.substring(i + 1))
        }.toMap()

        params["error"]?.let { error ->
            val description = params["error_description"]
            return OAuthResult.Failure(OAuthError.Redirect(if (description != null) "$error: $description" else error))
        }
        val state = params["state"]
        if (state == null) {
            return OAuthResult.Failure(OAuthError.Redirect("Redirect is missing the state parameter"))
        }
        if (!Pkce.constantTimeEquals(state, transaction.state)) {
            return OAuthResult.Failure(OAuthError.Redirect("Redirect state does not match this attempt"))
        }
        if (transaction.isExpired(clock())) {
            return OAuthResult.Failure(OAuthError.Redirect("Authorization attempt expired"))
        }
        val code = params["code"]
        if (code.isNullOrBlank()) {
            return OAuthResult.Failure(OAuthError.Redirect("Redirect is missing the authorization code"))
        }
        return OAuthResult.Success(
            OAuthTokenSet(accessToken = "", tokenType = "code", scope = code)
        )
    }

    /** Form body for the token exchange (RFC 6749 section 4.1.3, with RFC 7636 section 4.5). */
    fun tokenExchangeForm(
        provider: OAuthProvider,
        transaction: PkceTransaction,
        authorizationCode: String,
    ): String {
        val params = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to authorizationCode,
            "redirect_uri" to provider.redirectUri,
            "client_id" to provider.clientId,
        )
        if (provider.usePkce) params["code_verifier"] = transaction.verifier
        provider.resource?.let { params["resource"] = it }
        return params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    /** Form body for a refresh (RFC 6749 section 6). */
    fun refreshForm(provider: OAuthProvider, refreshToken: String): String {
        val params = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to provider.clientId,
        )
        provider.resource?.let { params["resource"] = it }
        return params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * Parses a token endpoint response. A non-2xx status is a failure with the provider's own error
 * code, never a success. `expires_in` is converted to an absolute expiry so a stored token can be
 * checked without a clock at the call site.
 */
object OAuthTokenParser {

    fun parse(json: String, httpStatus: Int, nowMs: Long, previousRefreshToken: String? = null): OAuthResult {
        val obj = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return OAuthResult.Failure(OAuthError.Exchange("Token response was not JSON", httpStatus))
        if (httpStatus !in 200..299) {
            val code = obj.get("error")?.asString ?: "http_$httpStatus"
            val description = obj.get("error_description")?.asString
            return OAuthResult.Failure(
                OAuthError.Exchange(if (description != null) "$code: $description" else code, httpStatus)
            )
        }
        val access = obj.get("access_token")?.asString
        if (access.isNullOrBlank()) {
            return OAuthResult.Failure(OAuthError.Exchange("Token response had no access_token", httpStatus))
        }
        val expiresIn = obj.get("expires_in")?.asLong ?: 0L
        return OAuthResult.Success(
            OAuthTokenSet(
                accessToken = access,
                refreshToken = obj.get("refresh_token")?.asString ?: previousRefreshToken,
                tokenType = obj.get("token_type")?.asString ?: "Bearer",
                scope = obj.get("scope")?.asString,
                expiresAtMs = if (expiresIn > 0) nowMs + expiresIn * 1000L else 0L,
            )
        )
    }
}
