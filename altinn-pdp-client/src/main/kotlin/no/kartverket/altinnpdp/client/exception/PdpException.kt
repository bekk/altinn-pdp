package no.kartverket.altinnpdp.client.exception

/** Raised when the PDP call itself fails: a non-2xx response, or a response that could not be parsed. */
public class PdpException internal constructor(
    message: String,
    statusCode: Int? = null,
    responseBody: String? = null,
    cause: Throwable? = null,
) : AltinnPdpException(message, statusCode, responseBody, cause)
