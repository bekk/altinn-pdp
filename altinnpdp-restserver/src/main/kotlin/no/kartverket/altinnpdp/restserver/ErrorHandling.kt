package no.bekk.altinnpdp.restserver

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import no.bekk.altinnpdp.exception.AltinnPdpException

/**
 * Maps every exception that can escape a route to a JSON [ErrorResponse] instead of Ktor's
 * default plain-text/HTML error page, and to a status code that tells callers where the fault
 * lies: 400 for a request we could not understand, 502 when Altinn/Maskinporten itself failed,
 * 500 for anything unanticipated.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid request"))
        }
        exception<JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${cause.message}"))
        }
        // Thrown by ContentNegotiation itself (not the JSON converter) when it can't find a
        // converter for the request at all - typically a missing/wrong `Content-Type` header.
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${cause.message}"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Malformed request"))
        }
        exception<AltinnPdpException> { call, cause ->
            call.application.log.error("PDP call failed", cause)
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse(cause.message ?: "The call to Altinn failed"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
}
