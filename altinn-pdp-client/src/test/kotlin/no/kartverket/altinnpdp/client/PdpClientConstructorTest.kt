package no.kartverket.altinnpdp.client

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.response
import no.kartverket.altinnpdp.client.support.signedJwt
import no.kartverket.altinnpdp.client.support.testMaskinportenKey
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PdpClientConstructorTest {

    private val urls = mutableListOf<String>()

    private val http = OutgoingHttpClient { request ->
        urls += request.url.toString()
        when (request.url.path) {
            "/token" -> response(200, "application/json", maskinportenTokenResponse())
            AltinnTokenExchanger.EXCHANGE_PATH -> response(200, "text/plain", signedJwt(Instant.now().plusSeconds(300)))
            else -> response(200, "application/json", pdpDecisionResponse())
        }
    }

    @Test
    fun `sends every call through the given http client, to the environment's URLs`() = runBlocking {
        val client = PdpClient(
            environment = AltinnEnvironment.TT02,
            subscriptionKey = "subscription-key",
            maskinportenClientId = "my-client-id",
            maskinportenKey = testMaskinportenKey,
            httpClient = http,
        )

        client.authorizeSample()

        val platform = AltinnEnvironment.TT02.platformBaseUrl.toString()
        assertEquals(
            listOf(
                AltinnEnvironment.TT02.maskinportenTokenUrl.toString(),
                platform + AltinnTokenExchanger.EXCHANGE_PATH,
                platform + PdpClient.AUTHORIZE_PATH,
            ),
            urls,
        )
    }

    @Test
    fun `sends every call to the given URLs, with or without a trailing slash`() = runBlocking {
        for (platformBaseUrl in listOf("https://platform.altinn.test", "https://platform.altinn.test/")) {
            urls.clear()
            val client = PdpClient(
                platformBaseUrl = URI(platformBaseUrl),
                maskinportenTokenUrl = URI("https://maskinporten.test/token"),
                subscriptionKey = "subscription-key",
                maskinportenClientId = "my-client-id",
                maskinportenKey = testMaskinportenKey,
                httpClient = http,
            )

            client.authorizeSample()

            assertEquals(
                listOf(
                    "https://maskinporten.test/token",
                    "https://platform.altinn.test" + AltinnTokenExchanger.EXCHANGE_PATH,
                    "https://platform.altinn.test" + PdpClient.AUTHORIZE_PATH,
                ),
                urls,
                platformBaseUrl,
            )
        }
    }
}
