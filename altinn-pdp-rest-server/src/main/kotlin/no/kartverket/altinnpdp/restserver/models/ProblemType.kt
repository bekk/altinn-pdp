package no.kartverket.altinnpdp.restserver.models

enum class ProblemType(val title: String) {
    VALIDATION_ERROR("Validation failed"),
    MALFORMED_REQUEST("Malformed request"),
    INVALID_CACHE_CONTROL("Invalid Cache-Control header"),
    UPSTREAM_REJECTED("A service this API depends on rejected the request"),
    NOT_FOUND("Not found"),
    METHOD_NOT_ALLOWED("Method not allowed"),
    UPSTREAM_ERROR("A service this API depends on failed"),
    INTERNAL_ERROR("Internal error"),
    ;

    val uri: String get() = "tag:kartverket.no,2026:" + name.lowercase().replace('_', '-')
}
