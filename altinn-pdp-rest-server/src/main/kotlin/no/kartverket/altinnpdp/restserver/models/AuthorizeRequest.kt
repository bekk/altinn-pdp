package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation

@Serializable
data class AuthorizeRequest(
    val systemuserId: String? = null,
    val pid: String? = null,
    val resourceId: String? = null,
    val customerOrganizationNumber: String? = null,
    val action: String? = null,
) {
    fun parse(): Parsed {
        val errors = PdpRequestValidation.validate(systemuserId, pid, resourceId, customerOrganizationNumber, action)
        if (errors.isNotEmpty()) throw PdpValidationException(errors)
        return Parsed(
            if (pid.isNullOrBlank()) SystemUserId.parse(systemuserId!!) else PersonId.parse(pid),
            ResourceId.parse(resourceId!!),
            OrganizationNumber.parse(customerOrganizationNumber!!),
            ActionId.parse(action!!),
        )
    }

    data class Parsed(
        val subject: PdpSubject,
        val resourceId: ResourceId,
        val customerOrganizationNumber: OrganizationNumber,
        val action: ActionId,
    )
}
