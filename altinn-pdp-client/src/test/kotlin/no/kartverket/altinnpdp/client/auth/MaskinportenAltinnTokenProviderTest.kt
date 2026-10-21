package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.support.MutableClock
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TOKEN_PATH
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.maskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.support.serveBothTokens
import no.kartverket.altinnpdp.client.support.signedJwt
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

        val token = maskinportenAltinnTokenProvider(server).accessToken()

        assertEquals("Bearer mp-token", server.lastRequest(exchangePath).header("Authorization"))
        assertEquals(signedJwt(NOW.plusSeconds(300)), token.value)
    }

    @Test
    fun `serves both tokens from cache on later calls`() = runBlocking {
        server.serveBothTokens()
        val provider = maskinportenAltinnTokenProvider(server)

        repeat(3) { provider.accessToken() }

        assertEquals(1, server.requestCount(TOKEN_PATH))
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `exchanges again when the Altinn token is about to expire`() = runBlocking {
        server.serveBothTokens(altinnTokenLifetime = Duration.ofSeconds(300))
        val clock = MutableClock()
        val provider = maskinportenAltinnTokenProvider(server, clock)

        provider.accessToken()
        clock.advance(Duration.ofSeconds(300) - TokenCache.DEFAULT_REFRESH_LEEWAY)
        provider.accessToken()

        assertEquals(2, server.requestCount(exchangePath))
    }
}
