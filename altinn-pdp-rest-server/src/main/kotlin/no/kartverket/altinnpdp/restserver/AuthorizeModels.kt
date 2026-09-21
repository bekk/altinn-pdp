package no.kartverket.altinnpdp.restserver

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation

// Nullable so a missing field reaches our own validation as a MISSING error, rather than kotlinx
// throwing first and leaving us to parse its English wording back out.
@Serializable
data class AuthorizeRequest(
    val systemuserId: String? = null,
    val resourceId: String? = null,
    // The party (customer) whose access is being checked, not the calling system's own org number.
    val organizationNumber: String? = null,
    val action: String? = null,
) {
    fun validated(): Validated {
        val errors = PdpRequestValidation.validate(systemuserId, resourceId, organizationNumber, action)
        if (errors.isNotEmpty()) throw PdpValidationException(errors)
        return Validated(systemuserId!!, resourceId!!, organizationNumber!!, action!!)
    }

    data class Validated(
        val systemuserId: String,
        val resourceId: String,
        val organizationNumber: String,
        val action: String,
    )
}

// `decision` carries the raw XACML name alongside `permit` so a caller can tell an explicit DENY
// from NOT_APPLICABLE - a distinction `permit` collapses into the same `false`.
// The added fields are null-by-default so they stay absent for callers that predate them.
@Serializable
data class AuthorizeResponse(
    val permit: Boolean,
    val decision: String,
    val status: String? = null,
    val minimumAuthenticationLevel: Int? = null,
    val minimumAuthenticationLevelOrg: Int? = null,
)

@Serializable
data class FieldError(val field: String, val code: String, val message: String)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String = ErrorCode.INTERNAL_ERROR,
    val errors: List<FieldError>? = null,
)

object ErrorCode {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val MALFORMED_BODY = "MALFORMED_BODY"
    const val UPSTREAM_REJECTED = "UPSTREAM_REJECTED"
    const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
