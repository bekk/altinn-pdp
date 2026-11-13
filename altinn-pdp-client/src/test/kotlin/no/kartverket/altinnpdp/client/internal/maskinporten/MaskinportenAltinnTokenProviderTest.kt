package no.kartverket.altinnpdp.client.internal.maskinporten

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.internal.token.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TOKEN_PATH
import no.kartverket.altinnpdp.client.support.bothTokens
import no.kartverket.altinnpdp.client.support.maskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.support.recording
import no.kartverket.altinnpdp.client.support.signedJwt
import kotlin.test.Test
import kotlin.test.assertEquals

class MaskinportenAltinnTokenProviderTest {

    private val sent = mutableListOf<OutgoingRequest>()

    private val exchangePath = AltinnTokenExchanger.EXCHANGE_PATH

    private fun sentTo(path: String) = sent.filter { it.url.path == path }

    @Test
    fun `fetches a Maskinporten token and exchanges it for an Altinn token`() = runBlocking {
        val token = maskinportenAltinnTokenProvider(recording(sent, bothTokens())).accessToken()

        assertEquals("Bearer mp-token", sentTo(exchangePath).single().headers["Authorization"])
        assertEquals(signedJwt(NOW.plusSeconds(300)), token.value)
    }

    @Test
    fun `serves both tokens from cache on later calls`() = runBlocking {
        val provider = maskinportenAltinnTokenProvider(recording(sent, bothTokens()))

        repeat(3) { provider.accessToken() }

        assertEquals(1, sentTo(TOKEN_PATH).size)
        assertEquals(1, sentTo(exchangePath).size)
    }
}
