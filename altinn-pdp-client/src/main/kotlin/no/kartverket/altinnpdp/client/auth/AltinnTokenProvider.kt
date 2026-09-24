package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import java.time.Instant

interface AltinnTokenProvider {
    suspend fun getAltinnToken(): AltinnToken
}

sealed interface AccessToken {
    val value: String
    val expiresAt: Instant

    fun isExpired(now: Instant, leeway: Duration): Boolean = !now.plus(leeway).isBefore(expiresAt)
}

data class AltinnToken(override val value: String, override val expiresAt: Instant) : AccessToken {
    init {
        require(value.isNotBlank()) { "Altinn token is required" }
    }

    override fun toString(): String = "AltinnToken[value=***, expiresAt=$expiresAt]"
}

data class MaskinportenToken(override val value: String, override val expiresAt: Instant) : AccessToken {
    init {
        require(value.isNotBlank()) { "Maskinporten token is required" }
    }

    override fun toString(): String = "MaskinportenToken[value=***, expiresAt=$expiresAt]"
}
