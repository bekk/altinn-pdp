package no.kartverket.altinnpdp.client.auth

import java.net.URI
import java.time.Duration

data class MaskinportenConfig(
    val tokenUrl: String,
    val clientId: String,
    val jwk: String,
    val scopes: List<String>,
    val assertionLifetime: Duration = Duration.ofSeconds(60),
    val audience: String = deriveAudience(tokenUrl),
) {
    init {
        require(tokenUrl.isNotBlank()) { "tokenUrl is required" }
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
