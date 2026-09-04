package no.kartverket.altinn.pdp.exception

/**
 * Failure while asking the Altinn PDP (`POST /authorization/api/v1/authorize`) whether a subject
 * has access to a resource - a non-2xx response, or a response that could not be parsed.
 *
 * A lean stand-in for now: the original library roots this in a shared `TilgangsstyringException`
 * (body-length capping, [statusCode]/[responseBody] as `Optional`s) alongside `MaskinportenException`
 * and `AltinnException`. Revisit as a proper sealed hierarchy once those are ported too.
 */
class PdpException(
    message: String,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
