package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import no.kartverket.altinnpdp.client.support.TestKeys

class MaskinportenConfigTest {

    private fun config(
        tokenUrl: String = "https://test.maskinporten.no/token",
        clientId: String = "my-client-id",
        jwk: String = TestKeys.rsa.toJSONString(),
        scopes: List<String> = listOf(AltinnScopes.AUTHORIZE),
        assertionLifetime: Duration = Duration.ofSeconds(60),
    ) = MaskinportenConfig(
        tokenUrl = tokenUrl,
        clientId = clientId,
        jwk = jwk,
        scopes = scopes,
        assertionLifetime = assertionLifetime,
    )

    @Test
    fun `derives the audience as the issuer, with the trailing slash Maskinporten requires`() {
        // Maskinporten compares `aud` to its issuer exactly; drop the slash and every assertion
        // is rejected, which is not obvious from the one-line derivation.
        assertEquals("https://test.maskinporten.no/", config().audience)
        assertEquals("https://maskinporten.no/", config(tokenUrl = "https://maskinporten.no/token").audience)
    }

    @Test
    fun `keeps the port when deriving the audience`() {
        assertEquals("http://localhost:8080/", config(tokenUrl = "http://localhost:8080/token").audience)
    }

    @Test
    fun `an explicitly supplied audience wins over the derived one`() {
        val config = MaskinportenConfig(
            tokenUrl = "https://test.maskinporten.no/token",
            clientId = "my-client-id",
            jwk = TestKeys.rsa.toJSONString(),
            scopes = listOf(AltinnScopes.AUTHORIZE),
            audience = "https://something.else/",
        )

        assertEquals("https://something.else/", config.audience)
    }

    @Test
    fun `joins the scopes with spaces, as the scope claim requires`() {
        val config = config(scopes = listOf(AltinnScopes.RESOURCE_READ, AltinnScopes.RESOURCE_WRITE))

        assertEquals("altinn:resourceregistry/resource.read altinn:resourceregistry/resource.write", config.scopeString)
    }

    @Test
    fun `rejects missing configuration rather than failing on the first call`() {
        assertFailsWith<IllegalArgumentException> { config(tokenUrl = " ") }
        assertFailsWith<IllegalArgumentException> { config(clientId = "") }
        assertFailsWith<IllegalArgumentException> { config(jwk = "") }
        assertFailsWith<IllegalArgumentException> { config(scopes = emptyList()) }
    }

    @Test
    fun `rejects an assertion lifetime outside what Maskinporten allows`() {
        assertFailsWith<IllegalArgumentException> { config(assertionLifetime = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { config(assertionLifetime = Duration.ofSeconds(-1)) }
        assertFailsWith<IllegalArgumentException> {
            config(assertionLifetime = MaskinportenConfig.MAX_ASSERTION_LIFETIME.plusSeconds(1))
        }
    }

    @Test
    fun `allows an assertion lifetime exactly at the maximum`() {
        assertEquals(
            MaskinportenConfig.MAX_ASSERTION_LIFETIME,
            config(assertionLifetime = MaskinportenConfig.MAX_ASSERTION_LIFETIME).assertionLifetime,
        )
    }
}
