package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val code: ErrorCode,
    val errors: List<FieldError>? = null,
)
