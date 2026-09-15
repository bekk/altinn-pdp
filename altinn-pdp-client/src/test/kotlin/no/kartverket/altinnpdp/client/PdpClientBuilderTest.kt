package no.kartverket.altinnpdp.client

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.support.TestKeys

class PdpClientBuilderTest {

    private val jwk: String get() = TestKeys.rsa.toJSONString()

    private fun builder() = PdpClient.builder()
        .environment(AltinnEnvironment.TT02)
        .subscriptionKey("subscription-key")

    private object FakeTokenProvider : AltinnTokenProvider {
        override suspend fun getAltinnToken() = AccessToken("altinn-token", Instant.MAX)
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
            .tokenProvider(FakeTokenProvider)
            .build()

        assertNotNull(client)
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
