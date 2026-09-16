package no.kartverket.altinnpdp.client.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.signedJwt

class AltinnTokenExchangerTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val path = AltinnTokenExchanger.EXCHANGE_PATH

    private fun exchanger(server: TestHttpServer, baseUrl: String = server.baseUrl) =
        AltinnTokenExchanger(baseUrl, Timeouts.DEFAULT)

    @Test
    fun `sends the Maskinporten token as a bearer token on a GET`() = runBlocking {
        server.on(path) { TestResponse(body = signedJwt(NOW.plusSeconds(300))) }

        exchanger(server).exchange("maskinporten-token")

        val request = server.lastRequest(path)
        assertEquals("GET", request.method)
        assertEquals("Bearer maskinporten-token", request.header("Authorization"))
    }

    @Test
    fun `takes the expiry from the returned token's own exp claim`() = runBlocking {
        val expiresAt = NOW.plusSeconds(300)
        server.on(path) { TestResponse(body = signedJwt(expiresAt)) }

        val token = exchanger(server).exchange("maskinporten-token")

        assertEquals(expiresAt.epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `trims whitespace around the returned token`() = runBlocking {
        val jwt = signedJwt(NOW.plusSeconds(300))
        server.on(path) { TestResponse(body = "  $jwt\n") }

        assertEquals(jwt, exchanger(server).exchange("maskinporten-token").value)
    }

    @Test
    fun `fails when the token has no exp claim`() = runBlocking {
        server.on(path) { TestResponse(body = signedJwt(expiresAt = null)) }

        assertContains(
            assertFailsWith<AltinnException> { exchanger(server).exchange("maskinporten-token") }.message!!,
            "exp",
        )
    }

    @Test
    fun `appends the exchange path to a base URL that ends in a slash`() = runBlocking {
        server.on(path) { TestResponse(body = signedJwt(NOW.plusSeconds(300))) }

        exchanger(server, baseUrl = server.baseUrl + "/").exchange("maskinporten-token")

        assertEquals(1, server.requestCount(path), "a doubled slash would not have matched the context")
    }

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        server.on(path) { TestResponse(status = 401, body = "token rejected") }

        val e = assertFailsWith<AltinnException> { exchanger(server).exchange("maskinporten-token") }

        assertEquals(401, e.statusCode)
        assertEquals("token rejected", e.responseBody)
    }

    @Test
    fun `fails when Altinn answers with an empty body`() = runBlocking {
        server.on(path) { TestResponse(body = "") }

        assertContains(assertFailsWith<AltinnException> { exchanger(server).exchange("mp") }.message!!, "empty")
    }

    @Test
    fun `fails when the body is not a JWT at all`() = runBlocking {
        server.on(path) { TestResponse(body = "<html>gateway error</html>") }

        assertContains(assertFailsWith<AltinnException> { exchanger(server).exchange("mp") }.message!!, "JWT")
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        val exchanger = AltinnTokenExchanger("http://127.0.0.1:1", Timeouts.DEFAULT)

        assertContains(assertFailsWith<AltinnException> { exchanger.exchange("mp") }.message!!, "Altinn token exchange")
    }
}
