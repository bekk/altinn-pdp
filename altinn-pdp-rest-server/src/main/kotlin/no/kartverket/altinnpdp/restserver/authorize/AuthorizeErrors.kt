package no.kartverket.altinnpdp.restserver.authorize

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import no.kartverket.altinnpdp.restserver.authorize.cache.DecisionCacheUnavailableException
import no.kartverket.altinnpdp.restserver.problem.FieldError
import no.kartverket.altinnpdp.restserver.problem.ProblemType
import no.kartverket.altinnpdp.restserver.problem.ValidationCode
import no.kartverket.altinnpdp.restserver.problem.respondProblem

internal fun StatusPagesConfig.authorizeErrorResponses() {
    exception<InvalidRequestException> { call, cause ->
        call.respondProblem(ProblemType.VALIDATION_ERROR, HttpStatusCode.BadRequest, errors = cause.errors)
    }
    exception<InvalidCacheControlException> { call, _ ->
        call.respondProblem(
            ProblemType.VALIDATION_ERROR,
            HttpStatusCode.BadRequest,
            errors = listOf(FieldError(HttpHeaders.CacheControl, ValidationCode.INVALID_FORMAT, "max-age in Cache-Control must be a whole number of seconds, 0 or more")),
        )
    }
    exception<DecisionCacheUnavailableException> { call, _ ->
        call.respondProblem(ProblemType.DECISION_CACHE_UNAVAILABLE, HttpStatusCode.ServiceUnavailable)
    }
}
