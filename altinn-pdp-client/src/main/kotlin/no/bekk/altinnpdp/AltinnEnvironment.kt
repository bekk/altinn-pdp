package no.bekk.altinnpdp

/**
 * Which Altinn platform environment to call. Fixes, from one choice, the PDP/platform base URL
 * and the Maskinporten token endpoint - both confirmed values (the TT02 ones match the original
 * Java library's own usage; the PROD Maskinporten URL is straight from Maskinporten's own
 * `.well-known/oauth-authorization-server` metadata) - so picking [PROD] can't accidentally leave
 * either one still pointing at test.
 *
 * Deliberately does NOT fix the Maskinporten `resource` claim: the PROD value would have been a
 * guess (pattern-matched off the TT02 one, never independently confirmed), so it's left as a
 * required [PdpClient.Builder.maskinportenResource] call instead of a silently-wrong default.
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
