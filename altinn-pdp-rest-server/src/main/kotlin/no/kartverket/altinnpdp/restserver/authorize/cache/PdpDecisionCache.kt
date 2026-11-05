package no.kartverket.altinnpdp.restserver.authorize.cache

import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.restserver.authorize.AuthorizeRequest
import java.time.Duration

/** A decision and how long ago Altinn made it. */
data class AuthorizationWithAge(val authorization: PdpAuthorization, val age: Duration)

interface PdpDecisionCache : AutoCloseable {
    suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge

    override fun close() {}
}

object NoPdpDecisionCache : PdpDecisionCache {
    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge = AuthorizationWithAge(load(), Duration.ZERO)
}
