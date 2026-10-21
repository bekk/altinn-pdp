package no.kartverket.altinnpdp.client

import java.net.URI

public enum class AltinnEnvironment(public val platformBaseUrl: URI, public val maskinportenTokenUrl: URI) {
    TT02(
        platformBaseUrl = URI("https://platform.tt02.altinn.no"),
        maskinportenTokenUrl = URI("https://test.maskinporten.no/token"),
    ),
    PROD(
        platformBaseUrl = URI("https://platform.altinn.no"),
        maskinportenTokenUrl = URI("https://maskinporten.no/token"),
    ),
}
