package no.kartverket.altinnpdp.client

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.response
import no.kartverket.altinnpdp.client.support.testPdpClient
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PdpClientTimeoutTest {

    @Test
    fun `a stalled call fails on the request timeout`() = runBlocking {
        val client = testPdpClient(OutgoingHttpClient { throw HttpTimeoutException("request timed out") })

        val e = assertFailsWith<PdpException> { client.authorizeSample() }

        assertIs<HttpTimeoutException>(e.cause)
        assertContains(e.message!!, "Call to Altinn PDP failed")
    }

    @Test
    fun `a caller's own withTimeout bounds the whole lookup`(): Unit = runBlocking {
        val client = testPdpClient(
            OutgoingHttpClient {
                delay(400)
                response(200, "application/json", pdpDecisionResponse())
            },
        )

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { client.authorizeSample() }
        }
    }
}
