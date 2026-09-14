package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.support.MutableClock
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestKeys
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.signedJwt

/**
 * The provider chains two cached calls: a Maskinporten token, then an exchange for an Altinn one.
 * Both caches are exercised here through real HTTP so the nesting is covered end to end.
 */
class MaskinportenAltinnTokenProviderTest {

    /** One server per test, started and stopped around it rather than inside every test body. */
    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val tokenPath = "/token"
    private val exchangePath = AltinnTokenExchanger.EXCHANGE_PATH

    private fun provider(server: TestHttpServer, clock: MutableClock = MutableClock()) =
        MaskinportenAltinnTokenProvider(
            maskinportenClient = MaskinportenClient(
                MaskinportenConfig(
                    tokenUrl = server.baseUrl + tokenPath,
                    clientId = "my-client-id",
                    jwk = TestKeys.rsa.toJSONString(),
                    scopes = listOf(AltinnScopes.AUTHORIZE),
                ),
                clock = clock,
            ),
            exchanger = AltinnTokenExchanger(server.baseUrl, clock = clock),
            clock = clock,
        )

    private fun TestHttpServer.serveBothTokens(altinnTokenLifetime: Duration = Duration.ofSeconds(300)) = apply {
        on(tokenPath) { TestResponse(body = """{"access_token":"mp-token","expires_in":3600}""") }
        on(exchangePath) { TestResponse(body = signedJwt(NOW.plus(altinnTokenLifetime))) }
    }

    @Test
    fun `fetches a Maskinporten token and exchanges it for an Altinn token`() = runBlocking {
        server.serveBothTokens()

        val token = provider(server).getAltinnToken()

        assertEquals("Bearer mp-token", server.lastRequest(exchangePath).header("Authorization"))
        assertEquals(NOW.plusSeconds(300).epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `serves both tokens from cache on later calls`() = runBlocking {
        server.serveBothTokens()
        val provider = provider(server)

        repeat(3) { provider.getAltinnToken() }

        assertEquals(1, server.requestCount(tokenPath))
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `exposes the underlying Maskinporten token without exchanging again`() = runBlocking {
        server.serveBothTokens()
        val provider = provider(server)

        provider.getAltinnToken()

        assertEquals("mp-token", provider.getMaskinportenToken().value)
        assertEquals(1, server.requestCount(tokenPath))
        assertEquals(1, server.requestCount(exchangePath))
    }

    @Test
    fun `invalidate clears both caches so the next call refetches everything`() = runBlocking {
        server.serveBothTokens()
        val provider = provider(server)

        provider.getAltinnToken()
        provider.invalidate()
        provider.getAltinnToken()

        assertEquals(2, server.requestCount(tokenPath), "the Maskinporten token should be refetched too")
        assertEquals(2, server.requestCount(exchangePath))
    }

    @Test
    fun `concurrent callers on cold caches fetch one of each token`() = runBlocking {
        // Getting an Altinn token takes the Altinn cache's lock and then, inside the loader, the
        // Maskinporten cache's lock. That nesting is only safe while both are always acquired in
        // this order; reversing it anywhere would deadlock exactly here.
        server.serveBothTokens()
        val provider = provider(server)

        coroutineScope {
            List(20) { async(Dispatchers.Default) { provider.getAltinnToken() } }.awaitAll()
        }

        assertEquals(1, server.requestCount(tokenPath))
        assertEquals(1, server.requestCount(exchangePath))
    }
}
