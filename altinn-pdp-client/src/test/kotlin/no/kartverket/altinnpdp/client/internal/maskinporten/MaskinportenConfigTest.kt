package no.kartverket.altinnpdp.client.internal.maskinporten

import no.kartverket.altinnpdp.client.support.maskinportenConfig
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class MaskinportenConfigTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "https://test.maskinporten.no/token | https://test.maskinporten.no/",
            "https://maskinporten.no/token | https://maskinporten.no/",
            "http://localhost:8080/token | http://localhost:8080/",
        ],
    )
    fun `derives the audience as the issuer, with the trailing slash Maskinporten requires`(tokenUrl: String, audience: String) {
        assertEquals(audience, maskinportenConfig(tokenUrl = tokenUrl).audience)
    }
}
