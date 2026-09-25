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
import no.kartverket.altinnpdp.restserver.models.ErrorCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<PdpValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Validation failed",
                    code = ErrorCode.VALIDATION_ERROR,
                    errors = cause.errors.map { FieldError(it.field, it.code, it.message) },
                ),
            )
        }
        // kotlinx's text quotes the caller's body back at them, so it is logged, never returned.
        exception<JsonConvertException> { call, cause -> call.respondMalformedBody(cause) }
        exception<ContentTransformationException> { call, cause -> call.respondMalformedBody(cause) }
        exception<BadRequestException> { call, cause -> call.respondMalformedBody(cause) }
        exception<AltinnPdpException> { call, cause ->
            call.application.log.error("Call to Maskinporten or Altinn failed: statusCode=${cause.statusCode}", cause)
            call.respondUpstream(cause)
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

private suspend fun io.ktor.server.application.ApplicationCall.respondUpstream(cause: AltinnPdpException) {
    if (cause is PdpException && cause.statusCode == 400) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Altinn rejected the request", ErrorCode.UPSTREAM_REJECTED))
    } else {
        respond(HttpStatusCode.BadGateway, ErrorResponse("The call to Altinn failed", ErrorCode.UPSTREAM_ERROR))
    }
}
