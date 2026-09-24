package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    VALIDATION_ERROR,
    MALFORMED_BODY,
    UPSTREAM_REJECTED,
    UPSTREAM_ERROR,
    INTERNAL_ERROR,
}
