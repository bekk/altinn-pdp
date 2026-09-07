package no.bekk.altinnpdp.auth

import java.time.Duration
import java.time.Instant

/**
 * A source of a valid Altinn token. [MaskinportenAltinnTokenProvider] is the real implementation
 * (Maskinporten + token exchange); this interface is the seam `PdpClient` depends on so a caller
 * can supply their own token source instead, or fake it in tests.
 */
interface AltinnTokenProvider {
    suspend fun getAltinnToken(): AccessToken
}

/** An access token with its expiry. */
data class AccessToken(val value: String, val expiresAt: Instant) {
    /** Whether the token has expired, or expires within [leeway]. */
    fun isExpired(now: Instant, leeway: Duration): Boolean = !now.plus(leeway).isBefore(expiresAt)

    /** Masks the token value so it does not end up in logs by accident. */
    override fun toString(): String = "AccessToken[value=***, expiresAt=$expiresAt]"
}
