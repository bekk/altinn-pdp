package no.kartverket.altinn.pdp.auth

import java.net.URI
import java.time.Duration

/**
 * Configuration for the Maskinporten integration.
 *
 * @param tokenUrl the token endpoint, for example `https://test.maskinporten.no/token`
 * @param clientId client id issued by Maskinporten - sent as `iss` in the client assertion
 * @param jwk the private key as a JWK in JSON format, used to sign the client assertion
 * @param scopes the scopes requested, sent as a space-separated `scope` claim
 * @param resource optional `resource` claim - which API the token is intended for
 * @param assertionLifetime lifetime of the client assertion; at most [MAX_ASSERTION_LIFETIME]
 * @param audience `aud` in the client assertion - Maskinporten's issuer. Derived from [tokenUrl]
 *   by default; override only if Maskinporten requires something else.
 */
data class MaskinportenConfig(
    val tokenUrl: String,
    val clientId: String,
    val jwk: String,
    val scopes: List<String>,
    val resource: String? = null,
    val assertionLifetime: Duration = Duration.ofSeconds(60),
    val audience: String = deriveAudience(tokenUrl),
) {
    init {
        require(clientId.isNotBlank()) { "clientId is required" }
        require(jwk.isNotBlank()) { "jwk is required" }
        require(scopes.isNotEmpty()) { "at least one scope is required" }
        require(
            !assertionLifetime.isNegative && !assertionLifetime.isZero &&
                assertionLifetime <= MAX_ASSERTION_LIFETIME
        ) { "assertionLifetime must be between 1 and ${MAX_ASSERTION_LIFETIME.seconds} seconds" }
    }

    internal val scopeString: String get() = scopes.joinToString(" ")

    companion object {
        /** Maskinporten allows a client assertion lifetime of at most 120 seconds. */
        val MAX_ASSERTION_LIFETIME: Duration = Duration.ofSeconds(120)

        /**
         * Maskinporten requires `aud` to equal the issuer, that is the token URL without its
         * path (`https://test.maskinporten.no/token` -> `https://test.maskinporten.no/`).
         */
        private fun deriveAudience(tokenUrl: String): String {
            val uri = URI.create(tokenUrl)
            return "${uri.scheme}://${uri.authority}/"
        }
    }
}
