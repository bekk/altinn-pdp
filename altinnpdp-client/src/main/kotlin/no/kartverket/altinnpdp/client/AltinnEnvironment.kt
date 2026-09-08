package no.kartverket.altinnpdp.client

/**
 * Which Altinn platform environment to call. Fixes, from one choice, every value that has to
 * stay consistent across an environment: the PDP/platform base URL and the Maskinporten token
 * endpoint.
 */
enum class AltinnEnvironment(
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
