package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
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
        return Validated(
            SystemUserId(systemuserId!!),
            ResourceId(resourceId!!),
            OrganizationNumber(customerOrganizationNumber!!),
            ActionId(action!!),
        )
    }

    data class Validated(
        val systemuserId: SystemUserId,
        val resourceId: ResourceId,
        val customerOrganizationNumber: OrganizationNumber,
        val action: ActionId,
    )
}
