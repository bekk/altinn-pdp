package no.kartverket.altinnpdp.client.internal.token

import java.time.Duration
import java.time.Instant

private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")

internal fun isValidBearerToken(value: String): Boolean = BEARER_TOKEN.matches(value)

internal sealed interface ExpiringToken {
    val value: String
    val expiresAt: Instant

    fun isExpired(now: Instant, leeway: Duration): Boolean = !now.plus(leeway).isBefore(expiresAt)
}

internal data class AltinnToken(override val value: String, override val expiresAt: Instant) : ExpiringToken {
    override fun toString(): String = "AltinnToken[value=***, expiresAt=$expiresAt]"
}

internal data class MaskinportenToken(override val value: String, override val expiresAt: Instant) : ExpiringToken {
    override fun toString(): String = "MaskinportenToken[value=***, expiresAt=$expiresAt]"
}
