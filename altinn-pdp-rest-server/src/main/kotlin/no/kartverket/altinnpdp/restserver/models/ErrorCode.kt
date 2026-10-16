package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    VALIDATION_ERROR,
    MALFORMED_BODY,
    INVALID_CACHE_CONTROL,
    UPSTREAM_REJECTED,
    UPSTREAM_ERROR,
    INTERNAL_ERROR,
}
