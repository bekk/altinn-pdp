package no.kartverket.altinnpdp.restserver.models

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
