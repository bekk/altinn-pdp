package no.kartverket.altinnpdp.client

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpResponse
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.signedJwt
import no.kartverket.altinnpdp.client.support.testMaskinportenKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PdpClientConstructorTest {

    @Test
    fun `sends every call through the given http client, to the environment's URLs`() = runBlocking {
        val urls = mutableListOf<String>()
        val http = PdpHttpClient { request ->
            urls += request.url.toString()
            when (request.url.path) {
                "/token" -> PdpHttpResponse(200, maskinportenTokenResponse())
                AltinnTokenExchanger.EXCHANGE_PATH -> PdpHttpResponse(200, signedJwt(Instant.now().plusSeconds(300)))
                else -> PdpHttpResponse(200, pdpDecisionResponse())
            }
        }
        val client = PdpClient(
            environment = AltinnEnvironment.TT02,
            subscriptionKey = "subscription-key",
            maskinportenClientId = "my-client-id",
            maskinportenKey = testMaskinportenKey,
            httpClient = http,
        )

        client.authorizeSample()

        val platform = AltinnEnvironment.TT02.platformBaseUrl
        assertEquals(
            listOf(
                AltinnEnvironment.TT02.maskinportenTokenUrl,
                platform + AltinnTokenExchanger.EXCHANGE_PATH,
                platform + PdpClient.AUTHORIZE_PATH,
            ),
            urls,
        )
    }
}
