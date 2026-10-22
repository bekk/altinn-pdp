package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.exception.AltinnPdpException
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.restserver.models.ErrorCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError

internal fun StatusPagesConfig.errorResponses() {
    exception<JsonConvertException> { call, cause -> call.respondMalformedBody(cause) }
    exception<ContentTransformationException> { call, cause -> call.respondMalformedBody(cause) }
    exception<BadRequestException> { call, cause -> call.respondMalformedBody(cause) }
    exception<PdpValidationException> { call, cause -> call.respondValidationFailed(cause) }
    exception<InvalidCacheControlException> { call, _ ->
        call.respondProblem(HttpStatusCode.BadRequest, "Invalid max-age in Cache-Control", ErrorCode.INVALID_CACHE_CONTROL)
    }
    exception<AltinnPdpException> { call, cause ->
        call.application.log.error("Call to Maskinporten or Altinn failed: statusCode=${cause.statusCode}", cause)
        call.respondUpstream(cause)
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled exception", cause)
        call.respondProblem(HttpStatusCode.InternalServerError, "Internal server error", ErrorCode.INTERNAL_ERROR)
    }
}

private suspend fun ApplicationCall.respondValidationFailed(cause: PdpValidationException) {
    respondProblem(
        HttpStatusCode.BadRequest,
        "Validation failed",
        ErrorCode.VALIDATION_ERROR,
        cause.errors.map { FieldError(it.field, it.code, it.message) },
    )
}

private suspend fun ApplicationCall.respondMalformedBody(cause: Throwable) {
    val causes = generateSequence(cause) { it.cause }.joinToString(" caused by ") { it.javaClass.name }
    application.log.warn("Malformed request body: $causes")
    respondProblem(HttpStatusCode.BadRequest, "Malformed request body", ErrorCode.MALFORMED_REQUEST)
}

private suspend fun ApplicationCall.respondUpstream(cause: AltinnPdpException) {
    if (cause is PdpException && cause.statusCode == 400) {
        respondProblem(HttpStatusCode.BadRequest, "Altinn rejected the request", ErrorCode.UPSTREAM_REJECTED)
    } else {
        respondProblem(HttpStatusCode.BadGateway, "The call to Altinn failed", ErrorCode.UPSTREAM_ERROR)
    }
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String,
    code: ErrorCode,
    errors: List<FieldError>? = null,
) = respondText(
    Json.encodeToString(ErrorResponse.serializer(), ErrorResponse.of(status, detail, code, errors)),
    ContentType.Application.ProblemJson,
    status,
)
