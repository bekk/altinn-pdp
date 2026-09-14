package no.kartverket.altinnpdp.client.exception

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
