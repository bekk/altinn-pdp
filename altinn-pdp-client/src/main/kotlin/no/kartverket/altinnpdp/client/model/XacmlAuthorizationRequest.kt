package no.kartverket.altinnpdp.client.model

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId

@Serializable
internal data class XacmlAuthorizationRequest(val request: Request) {

    @Serializable
    data class Request(
        val returnPolicyIdList: Boolean,
        val accessSubject: List<Category>,
        val action: List<Category>,
        val resource: List<Category>,
    )

    @Serializable
    data class Category(val attribute: List<Attribute>) {
        companion object {
            fun of(vararg attributes: Attribute) = Category(attributes.toList())
        }
    }

    @Serializable
    data class Attribute(val attributeId: String, val value: String)

    companion object {
        const val ATTRIBUTE_SYSTEMUSER_UUID = "urn:altinn:systemuser:uuid"

        const val ATTRIBUTE_ACTION_ID = "urn:oasis:names:tc:xacml:1.0:action:action-id"

        const val ATTRIBUTE_RESOURCE = "urn:altinn:resource"

        const val ATTRIBUTE_ORGANIZATION_NUMBER = "urn:altinn:organization:identifier-no"

        fun forSystemUser(
            systemuserId: SystemUserId,
            resourceId: ResourceId,
            customerOrganizationNumber: OrganizationNumber,
            action: ActionId,
        ): XacmlAuthorizationRequest = XacmlAuthorizationRequest(
            Request(
                returnPolicyIdList = true,
                accessSubject = listOf(Category.of(Attribute(ATTRIBUTE_SYSTEMUSER_UUID, systemuserId.value))),
                action = listOf(Category.of(Attribute(ATTRIBUTE_ACTION_ID, action.value))),
                resource = listOf(
                    Category.of(
                        Attribute(ATTRIBUTE_RESOURCE, resourceId.value),
                        Attribute(ATTRIBUTE_ORGANIZATION_NUMBER, customerOrganizationNumber.value),
                    ),
                ),
            ),
        )
    }
}
