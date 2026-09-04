package no.kartverket.altinn.pdp.model

import kotlinx.serialization.Serializable

/**
 * Body of a PDP authorization request, built for the one shape `PdpClient` needs: "does this
 * systembruker have access to this resource for this org and action".
 *
 * Internal - this is wire format for the PDP call, not part of the client's public API.
 *
 * See https://docs.altinn.studio/nb/authorization/guides/resource-owner/system-user/#autorisasjon-av-systembruker
 * for the full request/response shape.
 */
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
        /** `urn:altinn:systemuser:uuid` - the systembruker id from the token's `authorization_details`. */
        const val ATTRIBUTE_SYSTEMUSER_UUID = "urn:altinn:systemuser:uuid"

        /** `urn:oasis:names:tc:xacml:1.0:action:action-id` - e.g. `"read"` or `"write"`. */
        const val ATTRIBUTE_ACTION_ID = "urn:oasis:names:tc:xacml:1.0:action:action-id"

        /** `urn:altinn:resource` - the resource's identifier in the Resource Registry. */
        const val ATTRIBUTE_RESOURCE = "urn:altinn:resource"

        /** `urn:altinn:organization:identifier-no` - a plain Norwegian org number, no ISO6523 prefix. */
        const val ATTRIBUTE_ORGANIZATION_NUMBER = "urn:altinn:organization:identifier-no"

        fun forSystemUser(
            systemuserId: String,
            resourceId: String,
            organizationNumber: String,
            action: String,
        ): XacmlAuthorizationRequest = XacmlAuthorizationRequest(
            Request(
                returnPolicyIdList = true,
                accessSubject = listOf(Category.of(Attribute(ATTRIBUTE_SYSTEMUSER_UUID, systemuserId))),
                action = listOf(Category.of(Attribute(ATTRIBUTE_ACTION_ID, action))),
                resource = listOf(
                    Category.of(
                        Attribute(ATTRIBUTE_RESOURCE, resourceId),
                        Attribute(ATTRIBUTE_ORGANIZATION_NUMBER, organizationNumber),
                    ),
                ),
            ),
        )
    }
}
