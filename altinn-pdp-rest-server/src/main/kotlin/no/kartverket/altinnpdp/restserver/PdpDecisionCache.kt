package no.kartverket.altinnpdp.restserver

import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import java.time.Duration
import kotlin.time.measureTimedValue
import kotlin.time.toJavaDuration

/** A decision and how long ago Altinn made it. */
data class AuthorizationWithAge(val authorization: PdpAuthorization, val age: Duration)

interface PdpDecisionCache {
    suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge
}

object NoPdpDecisionCache : PdpDecisionCache {
    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge {
        val (authorization, age) = measureTimedValue { load() }
        return AuthorizationWithAge(authorization, age.toJavaDuration())
    }
}
