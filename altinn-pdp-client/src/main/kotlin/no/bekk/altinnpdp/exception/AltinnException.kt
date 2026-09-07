package no.bekk.altinnpdp.exception

/** Failure while exchanging a Maskinporten token for an Altinn token. */
class AltinnException(
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
