package no.kartverket.altinnpdp.client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.slowly
import no.kartverket.altinnpdp.client.support.testPdpClient
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
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

    private val authorizePath = PdpClient.AUTHORIZE_PATH

    @Test
    fun `a stalled call fails on the request timeout`() = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = pdpDecisionResponse())))
        val client = testPdpClient(
            server.baseUrl,
            httpClient = JavaPdpHttpClient(HttpClient.newHttpClient(), Duration.ofMillis(100)),
        )

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertIs<HttpTimeoutException>(e.cause)
        assertContains(e.message!!, "Call to Altinn PDP failed")
    }

    @Test
    fun `a caller's own withTimeout bounds the whole lookup`(): Unit = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = pdpDecisionResponse())))
        val client = testPdpClient(server.baseUrl)

        // A PdpException here would break the caller's own withTimeout.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { client.authorizeSample() }
        }
    }
}
