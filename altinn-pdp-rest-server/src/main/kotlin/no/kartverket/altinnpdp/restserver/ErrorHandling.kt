package no.kartverket.altinnpdp.restserver

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import no.kartverket.altinnpdp.client.exception.AltinnPdpException
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.exception.PdpValidationException

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<PdpValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Validation failed",
                    code = ErrorCode.VALIDATION_ERROR,
                    errors = cause.errors.map { FieldError(it.field, it.code.name, it.message) },
                ),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Invalid request", ErrorCode.VALIDATION_ERROR),
            )
        }
        // kotlinx's own text names our Json builder and quotes the caller's body back at them, so it
        // is logged rather than returned.
        exception<JsonConvertException> { call, cause -> call.respondMalformedBody(cause) }
        exception<ContentTransformationException> { call, cause -> call.respondMalformedBody(cause) }
        exception<BadRequestException> { call, cause -> call.respondMalformedBody(cause) }
        exception<PdpException> { call, cause ->
            call.application.log.error(
                "PDP call failed: statusCode=${cause.statusCode}, responseBody=${cause.responseBody}",
                cause,
            )
            call.respondUpstream(cause.statusCode)
        }
        exception<AltinnPdpException> { call, cause ->
            call.application.log.error(
                "Maskinporten/Altinn call failed: statusCode=${cause.statusCode}, responseBody=${cause.responseBody}",
                cause,
            )
            call.respondUpstream(cause.statusCode)
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", ErrorCode.INTERNAL_ERROR),
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMalformedBody(cause: Throwable) {
    application.log.warn("Malformed request body", cause)
    respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body", ErrorCode.MALFORMED_BODY))
}

// Only Altinn's own 400 means the request we built was wrong. 401, 403 and 429 are our credentials
// and our quota, so they are ours to answer for, not the caller's.
private suspend fun io.ktor.server.application.ApplicationCall.respondUpstream(statusCode: Int?) {
    if (statusCode == 400) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Altinn rejected the request", ErrorCode.UPSTREAM_REJECTED))
    } else {
        respond(HttpStatusCode.BadGateway, ErrorResponse("The call to Altinn failed", ErrorCode.UPSTREAM_ERROR))
    }
}
