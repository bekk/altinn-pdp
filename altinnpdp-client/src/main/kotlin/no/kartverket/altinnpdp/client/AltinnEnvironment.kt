package no.kartverket.altinnpdp.client

/**
 * Which Altinn platform environment to call. Fixes, from one choice, every value that has to
 * stay consistent across an environment: the PDP/platform base URL, the Maskinporten token
 * endpoint, and (where confirmed) the `resource` claim sent to Maskinporten - so picking [PROD]
 * can't accidentally leave any of these still pointing at test.
 *
 * [maskinportenResource] is nullable per environment rather than always present: the TT02 value
 * matches the original Java library's own usage, but the PROD value would only ever have been a
 * guess (pattern-matched off the TT02 one, never independently confirmed) - so [PROD] leaves it
 * `null`, forcing [PdpClient.Builder.maskinportenResource] to be called explicitly instead of
 * silently defaulting to something wrong. Fill in the confirmed value here once someone verifies
 * it against a real prod call, the same way the TT02 value and both `maskinportenTokenUrl` values
 * were confirmed.
 */
enum class AltinnEnvironment(
    internal val platformBaseUrl: String,
    internal val maskinportenTokenUrl: String,
    internal val maskinportenResource: String?,
) {
    TT02(
        platformBaseUrl = "https://platform.tt02.altinn.no",
        maskinportenTokenUrl = "https://test.maskinporten.no/token",
        maskinportenResource = "https://tt02.altinn.no",
    ),
    PROD(
        platformBaseUrl = "https://platform.altinn.no",
        maskinportenTokenUrl = "https://maskinporten.no/token",
        maskinportenResource = null, // Not confirmed; leave null to force explicit override in PdpClient.Builder
    ),
}
