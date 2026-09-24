package no.kartverket.altinnpdp.client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.auth.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TOKEN_PATH
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.maskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.signedJwt
import no.kartverket.altinnpdp.client.support.slowly
import no.kartverket.altinnpdp.client.support.testPdpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Only timeouts are asserted on: a sleep is a floor, so a slow machine cannot pass these by luck. */
class PdpClientTimeoutTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val exchangePath = AltinnTokenExchanger.EXCHANGE_PATH
    private val authorizePath = PdpClient.AUTHORIZE_PATH

    private val generous = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofSeconds(5))

    /** A client whose own budget is [total], behind a token provider that is never the bottleneck. */
    private fun clientWithBudget(total: Duration) = testPdpClient(
        server.baseUrl,
        tokenProvider = maskinportenAltinnTokenProvider(server, generous),
        timeouts = Timeouts(request = Duration.ofSeconds(5), total = total),
    )

    @Test
    fun `one stalled call fails on the request timeout, well inside the budget`() = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = pdpDecisionResponse())))
        val client = testPdpClient(
            server.baseUrl,
            timeouts = Timeouts(request = Duration.ofMillis(100), total = Duration.ofSeconds(10)),
        )

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertIs<HttpTimeoutException>(e.cause)
        assertContains(e.message!!, "Call to Altinn PDP failed")
    }

    @Test
    fun `the total budget bounds the whole lookup, not each call within it`() = runBlocking {
        server.on(TOKEN_PATH, slowly(250, TestResponse(body = maskinportenTokenResponse())))
        server.on(exchangePath, slowly(250, TestResponse(body = signedJwt(NOW.plusSeconds(300)), contentType = "text/plain")))
        server.on(authorizePath, slowly(250, TestResponse(body = pdpDecisionResponse())))

        val e = assertFailsWith<PdpException> { clientWithBudget(Duration.ofMillis(600)).authorizeSample() }

        assertContains(e.message!!, "time budget")
        assertContains(e.message!!, "600 ms")
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `a caller's own timeout is not reported as the client's budget`(): Unit = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = pdpDecisionResponse())))
        val client = testPdpClient(server.baseUrl, timeouts = generous)

        // A PdpException here would break the caller's own withTimeout.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { client.authorizeSample() }
        }
    }

    @Test
    fun `a budget spent fetching a token is still reported as the lookup's`() = runBlocking {
        server.on(TOKEN_PATH, slowly(400, TestResponse(body = maskinportenTokenResponse())))
        server.on(exchangePath) { TestResponse(body = signedJwt(NOW.plusSeconds(300)), contentType = "text/plain") }
        server.on(authorizePath) { TestResponse(body = pdpDecisionResponse()) }

        val e = assertFailsWith<PdpException> { clientWithBudget(Duration.ofMillis(200)).authorizeSample() }

        assertContains(e.message!!, "The PDP authorization lookup")
        assertContains(e.message!!, "200 ms")
    }
}
