package no.kartverket.altinnpdp.client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import no.kartverket.altinnpdp.client.support.RecordedRequest
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
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

    private object InstantTokenProvider : AltinnTokenProvider {
        override suspend fun getAltinnToken() = AccessToken("altinn-token", Instant.MAX)
    }

    private fun slowly(millis: Long, response: TestResponse): (RecordedRequest) -> TestResponse = {
        Thread.sleep(millis)
        response
    }

    private fun client(requestTimeout: Duration) = PdpClient(
        platformBaseUrl = server.baseUrl,
        tokenProvider = InstantTokenProvider,
        subscriptionKey = "subscription-key",
        httpClient = JavaPdpHttpClient(HttpClient.newHttpClient(), requestTimeout),
    )

    private suspend fun PdpClient.authorizeSample() =
        authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "test-resource", "923609016", "read")

    @Test
    fun `a stalled call fails on the request timeout`() = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""")))

        val e = assertFailsWith<PdpException> { client(Duration.ofMillis(100)).authorizeSample() }

        assertIs<HttpTimeoutException>(e.cause)
        assertContains(e.message!!, "Call to Altinn PDP failed")
    }

    @Test
    fun `a caller's own withTimeout bounds the whole lookup`(): Unit = runBlocking {
        server.on(authorizePath, slowly(400, TestResponse(body = """{"Response":[{"Decision":"Permit"}]}""")))

        // A PdpException here would break the caller's own withTimeout.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { client(Duration.ofSeconds(5)).authorizeSample() }
        }
    }
}
