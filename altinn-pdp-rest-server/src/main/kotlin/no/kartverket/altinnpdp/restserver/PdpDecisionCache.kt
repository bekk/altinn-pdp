package no.kartverket.altinnpdp.restserver

import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import java.time.Duration

interface PdpDecisionCache {
    suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): PdpAuthorization
}

object NoPdpDecisionCache : PdpDecisionCache {
    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): PdpAuthorization = load()
}
