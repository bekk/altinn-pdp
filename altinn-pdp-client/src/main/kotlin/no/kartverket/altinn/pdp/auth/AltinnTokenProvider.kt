package no.kartverket.altinn.pdp.auth

import java.time.Instant

/**
 * Placeholder seam until the Maskinporten/token-exchange chain is ported - just enough for
 * `PdpClient` to depend on an abstraction rather than a concrete token source. A caller can
 * already implement this directly (or fake it in tests) without waiting on that port.
 */
interface AltinnTokenProvider {
    suspend fun getAltinnToken(): AccessToken
}

/** An access token with its expiry. */
data class AccessToken(val value: String, val expiresAt: Instant) {
    /** Masks the token value so it does not end up in logs by accident. */
    override fun toString(): String = "AccessToken[value=***, expiresAt=$expiresAt]"
}
