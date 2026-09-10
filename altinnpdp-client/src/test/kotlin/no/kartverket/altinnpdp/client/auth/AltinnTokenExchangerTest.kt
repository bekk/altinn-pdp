package no.kartverket.altinnpdp.client.auth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.fixedClock
import no.kartverket.altinnpdp.client.support.signedJwt

class AltinnTokenExchangerTest {

    private val path = AltinnTokenExchanger.EXCHANGE_PATH

    private fun exchanger(server: TestHttpServer, baseUrl: String = server.baseUrl) =
        AltinnTokenExchanger(baseUrl, clock = fixedClock())

    @Test
    fun `sends the Maskinporten token as a bearer token on a GET`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = signedJwt(NOW.plusSeconds(300))) }

            exchanger(server).exchange("maskinporten-token")

            val request = server.lastRequest(path)
            assertEquals("GET", request.method)
            assertEquals("Bearer maskinporten-token", request.header("Authorization"))
        }
    }

    @Test
    fun `takes the expiry from the returned token's own exp claim`() = runBlocking {
        TestHttpServer.start().use { server ->
            val expiresAt = NOW.plusSeconds(300)
            server.on(path) { TestResponse(body = signedJwt(expiresAt)) }

            val token = exchanger(server).exchange("maskinporten-token")

            assertEquals(expiresAt.epochSecond, token.expiresAt.epochSecond)
        }
    }

    @Test
    fun `unwraps a token that Altinn returned wrapped in quotes`() = runBlocking {
        // Some Altinn environments answer with the JWT as a quoted JSON string rather than raw
        // text. Without the unwrapping this looks like a harmless no-op and gets deleted.
        TestHttpServer.start().use { server ->
            val jwt = signedJwt(NOW.plusSeconds(300))
            server.on(path) { TestResponse(body = "\"$jwt\"") }

            assertEquals(jwt, exchanger(server).exchange("maskinporten-token").value)
        }
    }

    @Test
    fun `trims whitespace around the returned token`() = runBlocking {
        TestHttpServer.start().use { server ->
            val jwt = signedJwt(NOW.plusSeconds(300))
            server.on(path) { TestResponse(body = "  $jwt\n") }

            assertEquals(jwt, exchanger(server).exchange("maskinporten-token").value)
        }
    }

    @Test
    fun `falls back to a short lifetime when the token has no exp claim`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = signedJwt(expiresAt = null)) }

            assertEquals(NOW.plusSeconds(60), exchanger(server).exchange("maskinporten-token").expiresAt)
        }
    }

    @Test
    fun `appends the exchange path to a base URL that ends in a slash`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = signedJwt(NOW.plusSeconds(300))) }

            exchanger(server, baseUrl = server.baseUrl + "/").exchange("maskinporten-token")

            assertEquals(1, server.requestCount(path), "a doubled slash would not have matched the context")
        }
    }

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(status = 401, body = "token rejected") }

            val e = assertFailsWith<AltinnException> { exchanger(server).exchange("maskinporten-token") }

            assertEquals(401, e.statusCode)
            assertEquals("token rejected", e.responseBody)
        }
    }

    @Test
    fun `fails when Altinn answers with an empty body`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = "") }

            assertContains(assertFailsWith<AltinnException> { exchanger(server).exchange("mp") }.message!!, "empty")
        }
    }

    @Test
    fun `fails when Altinn answers with quotes but no token inside them`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = "\"\"") }

            assertContains(assertFailsWith<AltinnException> { exchanger(server).exchange("mp") }.message!!, "empty")
        }
    }

    @Test
    fun `fails when the body is not a JWT at all`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(path) { TestResponse(body = "<html>gateway error</html>") }

            assertContains(assertFailsWith<AltinnException> { exchanger(server).exchange("mp") }.message!!, "JWT")
        }
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        val exchanger = AltinnTokenExchanger("http://127.0.0.1:1", clock = fixedClock())

        assertContains(assertFailsWith<AltinnException> { exchanger.exchange("mp") }.message!!, "Altinn token exchange")
    }
}
