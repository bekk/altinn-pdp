package no.kartverket.altinnpdp.client

public enum class AltinnEnvironment(
    internal val platformBaseUrl: String,
    internal val maskinportenTokenUrl: String,
) {
    TT02(
        platformBaseUrl = "https://platform.tt02.altinn.no",
        maskinportenTokenUrl = "https://test.maskinporten.no/token",
    ),
    PROD(
        platformBaseUrl = "https://platform.altinn.no",
        maskinportenTokenUrl = "https://maskinporten.no/token",
    ),
}
