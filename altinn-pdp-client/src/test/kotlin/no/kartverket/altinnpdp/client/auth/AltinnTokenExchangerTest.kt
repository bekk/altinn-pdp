package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.signedJwt
import no.kartverket.altinnpdp.client.support.testHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AltinnTokenExchangerTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val path = AltinnTokenExchanger.EXCHANGE_PATH

    private fun exchanger() = AltinnTokenExchanger(server.baseUrl, testHttpClient)

    private val maskinportenToken = MaskinportenToken("maskinporten-token", NOW.plusSeconds(300))

    @Test
    fun `sends the Maskinporten token as a bearer token on a GET`() = runBlocking {
        server.on(path) { TestResponse(body = signedJwt(NOW.plusSeconds(300))) }

        exchanger().exchange(maskinportenToken)

        val request = server.lastRequest(path)
        assertEquals("GET", request.method)
        assertEquals("Bearer maskinporten-token", request.header("Authorization"))
    }

    @Test
    fun `takes the expiry from the returned token's own exp claim`() = runBlocking {
        val expiresAt = NOW.plusSeconds(300)
        server.on(path) { TestResponse(body = signedJwt(expiresAt)) }

        val token = exchanger().exchange(maskinportenToken)

        assertEquals(expiresAt.epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `trims whitespace around the returned token`() = runBlocking {
        val jwt = signedJwt(NOW.plusSeconds(300))
        server.on(path) { TestResponse(body = "  $jwt\n") }

        assertEquals(jwt, exchanger().exchange(maskinportenToken).value)
    }

    @Test
    fun `fails on an answer that is not a token it can use`() = runBlocking {
        val cases = mapOf(
            "no exp claim" to (signedJwt(expiresAt = null) to "exp"),
            "an empty body" to ("" to "empty"),
            "a body that is not a JWT at all" to ("<html>gateway error</html>" to "JWT"),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case
            server.on(path) { TestResponse(body = body) }

            val e = assertFailsWith<AltinnException>(why) { exchanger().exchange(maskinportenToken) }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }
}
