package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PROBLEM_TYPE_PREFIX = "tag:kartverket.no,2026"

@Serializable
enum class ProblemType(val title: String) {
    @SerialName("$PROBLEM_TYPE_PREFIX:validation-error")
    VALIDATION_ERROR("Validation failed"),

    @SerialName("$PROBLEM_TYPE_PREFIX:malformed-request")
    MALFORMED_REQUEST("Malformed request"),

    @SerialName("$PROBLEM_TYPE_PREFIX:upstream-rejected")
    UPSTREAM_REJECTED("A service this API depends on rejected the request"),

    @SerialName("$PROBLEM_TYPE_PREFIX:not-found")
    NOT_FOUND("Not found"),

    @SerialName("$PROBLEM_TYPE_PREFIX:method-not-allowed")
    METHOD_NOT_ALLOWED("Method not allowed"),

    @SerialName("$PROBLEM_TYPE_PREFIX:upstream-error")
    UPSTREAM_ERROR("A service this API depends on failed"),

    @SerialName("$PROBLEM_TYPE_PREFIX:decision-cache-unavailable")
    DECISION_CACHE_UNAVAILABLE("The decision cache is unavailable"),

    @SerialName("$PROBLEM_TYPE_PREFIX:internal-error")
    INTERNAL_ERROR("Internal error"),
}
