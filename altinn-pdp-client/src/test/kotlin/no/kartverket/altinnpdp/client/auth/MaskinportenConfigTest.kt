package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.support.maskinportenConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaskinportenConfigTest {

    @Test
    fun `derives the audience as the issuer, with the trailing slash Maskinporten requires`() {
        val expected = mapOf(
            "https://test.maskinporten.no/token" to "https://test.maskinporten.no/",
            "https://maskinporten.no/token" to "https://maskinporten.no/",
            // The port is part of the issuer, so it has to survive.
            "http://localhost:8080/token" to "http://localhost:8080/",
        )
        for ((tokenUrl, audience) in expected) {
            assertEquals(audience, maskinportenConfig(tokenUrl = tokenUrl).audience, "for $tokenUrl")
        }
    }

    @Test
    fun `an explicitly supplied audience wins over the derived one`() {
        val config = maskinportenConfig(
            tokenUrl = "https://test.maskinporten.no/token",
            audience = "https://something.else/",
        )

        assertEquals("https://something.else/", config.audience)
    }

    @Test
    fun `joins the scopes with spaces, as the scope claim requires`() {
        val config = maskinportenConfig(scopes = listOf("scope-a", "scope-b"))

        assertEquals("scope-a scope-b", config.scopeString)
    }

    @Test
    fun `rejects missing configuration rather than failing on the first call`() {
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(tokenUrl = " ") }
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(clientId = "") }
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(jwk = "") }
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(scopes = emptyList()) }
    }

    @Test
    fun `rejects an assertion lifetime outside what Maskinporten allows`() {
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(assertionLifetime = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { maskinportenConfig(assertionLifetime = Duration.ofSeconds(-1)) }
        assertFailsWith<IllegalArgumentException> {
            maskinportenConfig(assertionLifetime = MaskinportenConfig.MAX_ASSERTION_LIFETIME.plusSeconds(1))
        }
    }

    @Test
    fun `allows an assertion lifetime exactly at the maximum`() {
        assertEquals(
            MaskinportenConfig.MAX_ASSERTION_LIFETIME,
            maskinportenConfig(assertionLifetime = MaskinportenConfig.MAX_ASSERTION_LIFETIME).assertionLifetime,
        )
    }
}
