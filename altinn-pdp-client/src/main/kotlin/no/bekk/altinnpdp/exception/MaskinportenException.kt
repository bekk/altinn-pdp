package no.bekk.altinnpdp.exception

/** Failure while fetching a token from Maskinporten. */
class MaskinportenException(
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
