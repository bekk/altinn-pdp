package no.kartverket.altinnpdp.client

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.support.TestKeys

/**
 * The builder is the only entry point a consumer is meant to use, so what it accepts and what it
 * refuses is public API. Nothing here makes a network call: [PdpClient.Builder.build] only
 * assembles the client, and the assertions below are about what it rejects before it gets that
 * far.
 */
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

    // --- what it refuses ---

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

        // Without the key API Management rejects every call with a 401, so refusing to build is
        // friendlier than a client that only fails once it is in use.
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
        // The message has to mention the alternative; a caller with their own token source
        // should not go looking for a client id they never needed.
        assertContains(e.message!!, "tokenProvider")
    }

    @Test
    fun `rejects a missing JWK, and names the way out`() {
        val e = assertFailsWith<IllegalArgumentException> { builder().maskinportenClientId("client-id").build() }

        assertContains(e.message!!, "maskinportenJwk")
        assertContains(e.message!!, "tokenProvider")
    }

    // --- what it builds ---

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

    // --- the token provider shortcut ---

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
        // The budget runs out inside the token provider, so nothing here reaches the network.
        val client = builder()
            .tokenProvider(SlowTokenProvider)
            .timeouts(Timeouts(total = Duration.ofMillis(50)))
            .build()

        val e = assertFailsWith<PdpException> {
            client.authorize("sys-1", "urn:altinn:resource:x", "923609016", "read")
        }

        assertContains(e.message!!, "50 ms")
    }

    @Test
    fun `tokenProvider skips the Maskinporten settings rather than validating them anyway`() {
        // A JWK this broken would fail the build on the Maskinporten path. Building anyway is
        // what proves the setters are ignored, not merely optional.
        val client = builder()
            .maskinportenClientId("client-id")
            .maskinportenJwk("not a jwk")
            .tokenProvider(FakeTokenProvider)
            .build()

        assertNotNull(client)
    }
}
