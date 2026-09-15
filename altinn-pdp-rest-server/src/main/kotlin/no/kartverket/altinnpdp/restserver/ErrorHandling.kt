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

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid request"))
        }
        exception<JsonConvertException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(bodyErrorMessage(cause)))
        }
        // ContentNegotiation throws this when it finds no converter at all, typically a missing
        // or wrong `Content-Type` - not a conversion that was attempted and failed.
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${cause.message}"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(bodyErrorMessage(cause)))
        }
        // cause.message carries Altinn's own response body, which can expose details of this
        // service's Altinn integration. Log it, never respond with it.
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

// The outer message names no field and leaks an internal class name, hence the walk to the inner
// JsonConvertException. The patterns match kotlinx's wording; ServerTest catches an upgrade.
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
