package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val code: ErrorCode = ErrorCode.INTERNAL_ERROR,
    val errors: List<FieldError>? = null,
)
