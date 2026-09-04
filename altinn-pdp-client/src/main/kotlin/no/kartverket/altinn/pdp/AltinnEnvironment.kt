package no.kartverket.altinn.pdp

/** Which Altinn platform environment to call - determines the base URL for every request. */
enum class AltinnEnvironment(internal val platformBaseUrl: String) {
    TT02("https://platform.tt02.altinn.no"),
    PROD("https://platform.altinn.no"),
}
