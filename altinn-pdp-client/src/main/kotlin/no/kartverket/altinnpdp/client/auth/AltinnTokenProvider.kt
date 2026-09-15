package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import java.time.Instant

interface AltinnTokenProvider {
    suspend fun getAltinnToken(): AccessToken
}

data class AccessToken(val value: String, val expiresAt: Instant) {
    fun isExpired(now: Instant, leeway: Duration): Boolean = !now.plus(leeway).isBefore(expiresAt)

    /** Masks the token value so it does not end up in logs by accident. */
    override fun toString(): String = "AccessToken[value=***, expiresAt=$expiresAt]"
}
