package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import no.kartverket.altinnpdp.client.exception.ValidationException
import no.kartverket.altinnpdp.client.validation.ValidationCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError
import no.kartverket.altinnpdp.restserver.models.ProblemType

internal fun StatusPagesConfig.errorResponses() {
    exception<JsonConvertException> { call, cause -> call.respondMalformedBody(cause) }
    exception<ContentTransformationException> { call, cause -> call.respondMalformedBody(cause) }
    exception<BadRequestException> { call, cause -> call.respondMalformedBody(cause) }
    exception<ValidationException> { call, cause -> call.respondValidationFailed(cause) }
    exception<InvalidCacheControlException> { call, _ ->
        call.respondProblem(
            ProblemType.VALIDATION_ERROR,
            HttpStatusCode.BadRequest,
            errors = listOf(FieldError(HttpHeaders.CacheControl, ValidationCode.INVALID_FORMAT, "max-age in Cache-Control must be a whole number of seconds, 0 or more")),
        )
    }
    exception<AltinnPdpException> { call, cause ->
        call.application.log.error("Call to Maskinporten or Altinn failed: statusCode=${cause.statusCode}", cause)
        call.respondUpstream(cause)
    }
    // Not logged per call: the cache logs once when Redis goes away and once when it is back.
    exception<DecisionCacheUnavailableException> { call, _ ->
        call.respondProblem(ProblemType.DECISION_CACHE_UNAVAILABLE, HttpStatusCode.ServiceUnavailable)
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled exception", cause)
        call.respondProblem(ProblemType.INTERNAL_ERROR, HttpStatusCode.InternalServerError)
    }
    status(HttpStatusCode.NotFound) { call, status ->
        call.respondProblem(ProblemType.NOT_FOUND, status, "There is no endpoint at this path")
    }
    status(HttpStatusCode.MethodNotAllowed) { call, status ->
        call.respondProblem(ProblemType.METHOD_NOT_ALLOWED, status, "The endpoint at this path does not accept this method")
    }
}

private suspend fun ApplicationCall.respondValidationFailed(cause: ValidationException) {
    respondProblem(
        ProblemType.VALIDATION_ERROR,
        HttpStatusCode.BadRequest,
        errors = cause.errors.map { FieldError(it.field, it.code, it.message) },
    )
}

private suspend fun ApplicationCall.respondMalformedBody(cause: Throwable) {
    val causes = generateSequence(cause) { it.cause }.joinToString(" caused by ") { it.javaClass.name }
    application.log.warn("Malformed request body: $causes")
    respondProblem(ProblemType.MALFORMED_REQUEST, HttpStatusCode.BadRequest, "Malformed request body")
}

private suspend fun ApplicationCall.respondUpstream(cause: AltinnPdpException) {
    if (cause is PdpException && cause.statusCode == 400) {
        respondProblem(ProblemType.UPSTREAM_REJECTED, HttpStatusCode.BadRequest, "Altinn rejected the request")
    } else {
        respondProblem(ProblemType.UPSTREAM_ERROR, HttpStatusCode.BadGateway, "The call to Altinn failed")
    }
}

private suspend fun ApplicationCall.respondProblem(
    type: ProblemType,
    status: HttpStatusCode,
    detail: String? = null,
    errors: List<FieldError>? = null,
) = respondText(
    Json.encodeToString(ErrorResponse.serializer(), ErrorResponse.of(type, status, detail, errors)),
    ContentType.Application.ProblemJson,
    status,
)
