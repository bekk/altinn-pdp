package no.kartverket.altinnpdp.client.exception

public class PdpException internal constructor(
    message: String,
    statusCode: Int? = null,
    responseBody: String? = null,
    cause: Throwable? = null,
) : AltinnPdpException(message, statusCode, responseBody, cause)
