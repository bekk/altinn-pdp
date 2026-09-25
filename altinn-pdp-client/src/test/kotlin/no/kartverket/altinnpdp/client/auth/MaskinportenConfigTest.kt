package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.support.maskinportenConfig
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
