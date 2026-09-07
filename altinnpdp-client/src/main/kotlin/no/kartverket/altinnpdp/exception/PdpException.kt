package no.kartverket.altinnpdp.exception

/**
 * Failure while asking the Altinn PDP (`POST /authorization/api/v1/authorize`) whether a subject
 * has access to a resource - a non-2xx response, or a response that could not be parsed.
 */
class PdpException(
    message: String,
    statusCode: Int? = null,
    responseBody: String? = null,
    cause: Throwable? = null,
) : AltinnPdpException(
    message = messageWithBody(message, responseBody),
    cause = cause,
    statusCode = statusCode,
    responseBody = responseBody,
)
