package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.auth.AltinnToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import java.time.Instant

internal class FakeTokenProvider : AltinnTokenProvider {
    override suspend fun getAltinnToken(): AltinnToken = AltinnToken("altinn-token", Instant.MAX)
}
