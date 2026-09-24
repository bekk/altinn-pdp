package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TOKEN_PATH
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.maskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.serveBothTokens
import no.kartverket.altinnpdp.client.support.signedJwt
import no.kartverket.altinnpdp.client.support.slowly
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaskinportenAltinnTokenProviderTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val exchangePath = AltinnTokenExchanger.EXCHANGE_PATH

    @Test
    fun `fetches a Maskinporten token and exchanges it for an Altinn token`() = runBlocking {
        server.serveBothTokens()

        val token = maskinportenAltinnTokenProvider(server).getAltinnToken()

        assertEquals("Bearer mp-token", server.lastRequest(exchangePath).header("Authorization"))
        assertEquals(NOW.plusSeconds(300).epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `serves both tokens from cache on later calls`() = runBlocking {
        server.serveBothTokens()
        val provider = maskinportenAltinnTokenProvider(server)

        repeat(3) { provider.getAltinnToken() }

        assertEquals(1, server.requestCount(TOKEN_PATH))
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `the total budget covers the exchange as well as the Maskinporten call`() = runBlocking {
        val timeouts = Timeouts(request = Duration.ofSeconds(5), total = Duration.ofMillis(350))
        server.on(TOKEN_PATH, slowly(200, TestResponse(body = maskinportenTokenResponse())))
        server.on(exchangePath, slowly(200, TestResponse(body = signedJwt(NOW.plusSeconds(300)))))
        val provider = maskinportenAltinnTokenProvider(server, timeouts)

        val e = assertFailsWith<AltinnException> { provider.getAltinnToken() }

        assertContains(e.message!!, "time budget")
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `concurrent callers on cold caches fetch one of each token`() = runBlocking {
        server.serveBothTokens()
        val provider = maskinportenAltinnTokenProvider(server)

        coroutineScope {
            List(20) { async(Dispatchers.Default) { provider.getAltinnToken() } }.awaitAll()
        }

        assertEquals(1, server.requestCount(TOKEN_PATH))
        assertEquals(1, server.requestCount(exchangePath))
    }
}
