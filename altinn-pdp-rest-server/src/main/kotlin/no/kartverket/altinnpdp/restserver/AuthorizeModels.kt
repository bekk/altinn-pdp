package no.kartverket.altinnpdp.restserver

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation

// Nullable so a missing field becomes our own MISSING error instead of a kotlinx parse failure.
@Serializable
data class AuthorizeRequest(
    val systemuserId: String? = null,
    val resourceId: String? = null,
    val customerOrganizationNumber: String? = null,
    val action: String? = null,
) {
    fun validated(): Validated {
        val errors = PdpRequestValidation.validate(systemuserId, resourceId, customerOrganizationNumber, action)
        if (errors.isNotEmpty()) throw PdpValidationException(errors)
        return Validated(systemuserId!!, resourceId!!, customerOrganizationNumber!!, action!!)
    }

    data class Validated(
        val systemuserId: String,
        val resourceId: String,
        val customerOrganizationNumber: String,
        val action: String,
    )
}

// `decision` carries the raw XACML name alongside `permit` so a caller can tell an explicit DENY
// from NOT_APPLICABLE - a distinction `permit` collapses into the same `false`.
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
