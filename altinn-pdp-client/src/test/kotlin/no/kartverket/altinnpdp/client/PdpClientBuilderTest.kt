package no.kartverket.altinnpdp.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.TestKeys
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PdpClientBuilderTest {

    private val jwk: String get() = TestKeys.rsa.toJSONString()

    private fun builder() = PdpClient.builder()
        .environment(AltinnEnvironment.TT02)
        .subscriptionKey("subscription-key")
        .timeouts(Timeouts.DEFAULT)

    private object FakeTokenProvider : AltinnTokenProvider {
        override suspend fun getAltinnToken() = AccessToken("altinn-token", Instant.MAX)
    }

    private object SlowTokenProvider : AltinnTokenProvider {
        override suspend fun getAltinnToken(): AccessToken {
            delay(500)
            return AccessToken("altinn-token", Instant.MAX)
        }
    }

    @Test
    fun `rejects a missing environment`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PdpClient.builder()
                .subscriptionKey("subscription-key")
                .tokenProvider(FakeTokenProvider)
                .build()
        }

        assertContains(e.message!!, "environment")
    }

    @Test
    fun `rejects a missing subscription key`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PdpClient.builder()
                .environment(AltinnEnvironment.TT02)
                .tokenProvider(FakeTokenProvider)
                .build()
        }

        assertContains(e.message!!, "subscriptionKey")
    }

    @Test
    fun `refuses to choose the timeouts for the caller, and names the way out`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PdpClient.builder()
                .environment(AltinnEnvironment.TT02)
                .subscriptionKey("subscription-key")
                .tokenProvider(FakeTokenProvider)
                .build()
        }

        assertContains(e.message!!, "timeouts")
        assertContains(e.message!!, "Timeouts.DEFAULT")
    }

    @Test
    fun `rejects a missing Maskinporten client id, and names the way out`() {
        val e = assertFailsWith<IllegalArgumentException> { builder().maskinportenJwk(jwk).build() }

        assertContains(e.message!!, "maskinportenClientId")
        assertContains(e.message!!, "tokenProvider")
    }

    @Test
    fun `rejects a missing JWK, and names the way out`() {
        val e = assertFailsWith<IllegalArgumentException> { builder().maskinportenClientId("client-id").build() }

        assertContains(e.message!!, "maskinportenJwk")
        assertContains(e.message!!, "tokenProvider")
    }

    @Test
    fun `builds a client from Maskinporten credentials`() {
        val client = builder()
            .maskinportenClientId("client-id")
            .maskinportenJwk(jwk)
            .build()

        assertNotNull(client)
    }

    @Test
    fun `rejects a JWK it cannot sign with at build time, not at the first call`() {
        val e = assertFailsWith<MaskinportenException> {
            builder()
                .maskinportenClientId("client-id")
                .maskinportenJwk(TestKeys.rsa.toPublicJWK().toJSONString())
                .build()
        }

        assertContains(e.message!!, "private key")
    }

    @Test
    fun `passes a Maskinporten token URL override on to the config rather than dropping it`() {
        val e = assertFailsWith<IllegalArgumentException> {
            builder()
                .maskinportenClientId("client-id")
                .maskinportenJwk(jwk)
                .maskinportenTokenUrl("")
                .build()
        }

        assertContains(e.message!!, "tokenUrl")
    }

    @Test
    fun `tokenProvider builds without any Maskinporten settings`() {
        val client = PdpClient.builder()
            .environment(AltinnEnvironment.TT02)
            .subscriptionKey("subscription-key")
            .timeouts(Timeouts.DEFAULT)
            .tokenProvider(FakeTokenProvider)
            .build()

        assertNotNull(client)
    }

    @Test
    fun `passes the timeouts on to the client rather than dropping them`() = runBlocking {
        val client = builder()
            .tokenProvider(SlowTokenProvider)
            .timeouts(Timeouts(total = Duration.ofMillis(50)))
            .build()

        val e = assertFailsWith<PdpException> {
            client.authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "test-resource", "923609016", "read")
        }

        assertContains(e.message!!, "50 ms")
    }

    @Test
    fun `tokenProvider skips the Maskinporten settings rather than validating them anyway`() {
        val client = builder()
            .maskinportenClientId("client-id")
            // The JWK is deliberately invalid: building anyway is what proves the Maskinporten
            // setters are ignored rather than merely optional.
            .maskinportenJwk("not a jwk")
            .tokenProvider(FakeTokenProvider)
            .build()

        assertNotNull(client)
    }
}
