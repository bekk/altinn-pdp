package no.kartverket.altinnpdp.client

import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnScopes
import no.kartverket.altinnpdp.client.auth.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenClient
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.RecordedRequest
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestKeys
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.signedJwt

/**
 * Only the failing direction is asserted on: a sleep is a floor, so a slow machine can make these
 * calls later but never early enough to pass by accident.
 */
class PdpClientTimeoutTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val tokenPath = "/token"
    private val exchangePath = AltinnTokenExchanger.EXCHANGE_PATH
    private val authorizePath = PdpClient.AUTHORIZE_PATH

    private object InstantTokenProvider : AltinnTokenProvider {
        override suspend fun getAltinnToken() = AccessToken("altinn-token", Instant.MAX)
    }

    private fun slowly(millis: Long, response: TestResponse): (RecordedRequest) -> TestResponse = {
        Thread.sleep(millis)
        response
    }

    private suspend fun PdpClient.authorizeSample() =
        authorize("sys-1", "urn:altinn:resource:x", "923609016", "read")

    @Test
    fun `one stalled call fails on the request timeout, well inside the budget`() = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""")))
        val client = PdpClient(
            platformBaseUrl = server.baseUrl,
            tokenProvider = InstantTokenProvider,
            subscriptionKey = "subscription-key",
            timeouts = Timeouts(request = Duration.ofMillis(100), total = Duration.ofSeconds(10)),
        )

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertIs<HttpTimeoutException>(e.cause)
        assertContains(e.message!!, "Call to Altinn PDP failed")
    }

    @Test
    fun `the total budget bounds the whole lookup, not each call within it`() = runBlocking {
        // No single call is near the 5 s request timeout, so without a budget all three succeed.
        server.on(tokenPath, slowly(250, TestResponse(body = """{"access_token":"mp-token","expires_in":3600}""")))
        server.on(exchangePath, slowly(250, TestResponse(body = signedJwt(Instant.now().plusSeconds(300)), contentType = "text/plain")))
        server.on(authorizePath, slowly(250, TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""")))

        val generous = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofSeconds(5))
        val client = PdpClient(
            platformBaseUrl = server.baseUrl,
            tokenProvider = MaskinportenAltinnTokenProvider(
                maskinportenClient = MaskinportenClient(maskinportenConfig(), generous),
                exchanger = AltinnTokenExchanger(server.baseUrl, generous),
                timeouts = generous,
            ),
            subscriptionKey = "subscription-key",
            timeouts = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofMillis(600)),
        )

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertContains(e.message!!, "time budget")
        assertContains(e.message!!, "600 ms")
        // Reached only once Maskinporten answered, so the budget was spent across calls.
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `a caller's own timeout is not reported as the client's budget`(): Unit = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""")))
        val client = PdpClient(
            platformBaseUrl = server.baseUrl,
            tokenProvider = InstantTokenProvider,
            subscriptionKey = "subscription-key",
            timeouts = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofSeconds(5)),
        )

        // Nothing of the client's has expired, so its budget must stay out of this and let the
        // caller's cancellation through - a PdpException here would break their withTimeout.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { client.authorizeSample() }
        }
    }

    @Test
    fun `a budget spent fetching a token is still reported as the lookup's`() = runBlocking {
        server.on(tokenPath, slowly(400, TestResponse(body = """{"access_token":"mp-token","expires_in":3600}""")))
        server.on(exchangePath) { TestResponse(body = signedJwt(Instant.now().plusSeconds(300)), contentType = "text/plain") }
        server.on(authorizePath) { TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""") }

        // The provider's own budget is nowhere near expiry, so the 200 ms it is cancelled by can
        // only be the lookup's - which is what the caller has to be told about.
        val generous = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofSeconds(5))
        val client = PdpClient(
            platformBaseUrl = server.baseUrl,
            tokenProvider = MaskinportenAltinnTokenProvider(
                maskinportenClient = MaskinportenClient(maskinportenConfig(), generous),
                exchanger = AltinnTokenExchanger(server.baseUrl, generous),
                timeouts = generous,
            ),
            subscriptionKey = "subscription-key",
            timeouts = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofMillis(200)),
        )

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertContains(e.message!!, "The PDP authorization lookup")
        assertContains(e.message!!, "200 ms")
    }

    private fun maskinportenConfig() = MaskinportenConfig(
        tokenUrl = server.baseUrl + tokenPath,
        clientId = "my-client-id",
        jwk = TestKeys.rsa.toJSONString(),
        scopes = listOf(AltinnScopes.AUTHORIZE),
    )
}
