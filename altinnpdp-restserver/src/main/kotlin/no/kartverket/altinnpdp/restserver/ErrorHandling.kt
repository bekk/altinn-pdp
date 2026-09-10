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

/**
 * Maps every exception that can escape a route to a JSON [ErrorResponse] instead of Ktor's
 * default plain-text/HTML error page, and to a status code that tells callers where the fault
 * lies: 400 for a request we could not understand (including a body Altinn itself rejected as
 * invalid), 502 when Altinn/Maskinporten failed for a reason unrelated to this request's content,
 * 500 for anything unanticipated.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid request"))
        }
        exception<JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(bodyErrorMessage(cause)))
        }
        // Thrown by ContentNegotiation itself (not the JSON converter) when it can't find a
        // converter for the request at all - typically a missing/wrong `Content-Type` header.
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${cause.message}"))
        }
        // Ktor wraps a failed body conversion (missing/blank field, wrong type, invalid JSON) in
        // this - the useful detail from the JSON converter is further down the cause chain.
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(bodyErrorMessage(cause)))
        }
        // A 4xx from Altinn means it rejected this specific request (e.g. an unknown resourceId
        // or malformed organizationNumber) - that is on the caller, so tell them, not just "bad
        // gateway". A 5xx, or no status at all (a network failure), is Altinn's fault, not theirs.
        //
        // cause.message already has Altinn's own response body appended (see
        // AltinnPdpException.messageWithBody) - that's useful in the logs but must never reach the
        // caller as-is, since it can carry details about our Altinn integration we don't want to
        // expose. Log the full detail, respond with a fixed message instead.
        exception<PdpException> { call, cause ->
            call.application.log.error(
                "PDP call failed: statusCode=${cause.statusCode}, responseBody=${cause.responseBody}",
                cause,
            )
            val status = cause.statusCode
            if (status != null && status in 400..499) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Altinn rejected the request"))
            } else {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("The call to Altinn failed"))
            }
        }
        // Maskinporten/Altinn token exchange failures: always a server-side credentials/infra
        // problem, never something the caller's request body could have caused.
        exception<AltinnPdpException> { call, cause ->
            call.application.log.error(
                "Maskinporten/Altinn call failed: statusCode=${cause.statusCode}, responseBody=${cause.responseBody}",
                cause,
            )
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("The call to Altinn failed"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
}

/**
 * The specific field-level detail (e.g. which field was missing) sits several levels down the
 * cause chain - [BadRequestException] wraps another [BadRequestException] wraps the
 * [JsonConvertException] with the actual message. Walks the chain to find it instead of
 * surfacing the outer "Failed to convert request body to class ..." message, which never names
 * the field and leaks an internal class name.
 */
private fun bodyErrorMessage(cause: Throwable): String {
    val detail = generateSequence(cause) { it.cause }
        .filterIsInstance<JsonConvertException>()
        .firstOrNull()
        ?.message
        ?.removePrefix("Illegal input: ")
        ?: return "Malformed request body"

    Regex("""Field '(\w+)' is required""").find(detail)?.let {
        return "Missing required field: ${it.groupValues[1]}"
    }
    Regex("""Fields \[(.+?)] are required""").find(detail)?.let {
        return "Missing required fields: ${it.groupValues[1]}"
    }
    return "Malformed request body: $detail"
}
