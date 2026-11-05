package no.kartverket.altinnpdp.restserver.authorize

import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.ResourceAttribute as PdpResourceAttribute

// Properties are nullable so that nulls or missing fields result in VALIDATION_ERROR rather than MALFORMED_REQUEST.
@Serializable
@JsonSchema.Required("resourceId", "customerOrganizationNumber", "action")
data class AuthorizeRequest(
    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Format("uuid")
    @JsonSchema.Description(
        "The system user, when your caller uses one. In the Maskinporten token it is the UUID in " +
            "`authorization_details[].systemuser_id`. Send either this or `pid`, not both.",
    )
    val systemuserId: String? = null,

    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Pattern(PdpRequestValidation.PID_PATTERN)
    @JsonSchema.Description(
        "The person, when your caller is logged in as one: their national identity number or D " +
            "number, from the `pid` claim in the Ansattporten token. It must have a valid date and check " +
            "digits. In test, synthetic persons such as those from Tenor are accepted too. In production, " +
            "only real persons are. Send either this or `systemuserId`, not both.",
    )
    val pid: String? = null,

    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Pattern(PdpRequestValidation.RESOURCE_ID_PATTERN)
    @JsonSchema.Description(
        "Your resource's id in the Altinn Resource Registry, for example " +
            "`altinn_access_management`. At least 4 characters, using lowercase letters, digits, underscore " +
            "and hyphen. An id that does not exist is not rejected with a `400`. You get a `200` with " +
            "`decision` `INDETERMINATE` instead.",
    )
    val resourceId: String? = null,

    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Pattern(PdpRequestValidation.ORGANIZATION_NUMBER_PATTERN)
    @JsonSchema.Description(
        """The customer: the organisation the system user or person acts on behalf of when calling your API.

- For a system user, it is the org number in `authorization_details[].systemuser_org` in the
  Maskinporten token. Do not use the `consumer` claim. That is the vendor's own org number.
- For a person, it is the organisation they chose when logging in, in
  `authorization_details[].authorized_parties[].orgno` in the Ansattporten token.

Send the 9 digits without the `0192:` prefix: `311718371`, not `0192:311718371`. The last digit
must be a valid check digit.""",
    )
    val customerOrganizationNumber: String? = null,

    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Description(
        "What the caller wants to do, as named in your resource's policy, for example `read` or " +
            "`write`. Any value that is not empty is accepted.",
    )
    val action: String? = null,

    @JsonSchema.Type(JsonType.ARRAY)
    @JsonSchema.Description(
        """The sub-resource to ask about, when your resource's policy has rules for sub-resources. Leave it out to
ask about the resource itself.

In Altinn Studio's policy editor, a rule lists its sub-resources under «Hvilken sub-ressurser skal regelen gjelde
for?». The first row of a sub-resource has Type `urn:altinn:resource`, and your `resourceId` as Id. Send each of
its other rows, the ones added with «Legg til en innsnevring av sub-ressursen», as one entry here. A rule only
matches when the request has every row of one of its sub-resources. Send each `type` only once.

A sub-resource with only the `urn:altinn:resource` row matches whatever is sent here. To give a sub-resource
access of its own, give its rule an action that the resource's other rules do not have.""",
    )
    val resourceAttributes: List<ResourceAttribute>? = null,
) {
    @Serializable
    @JsonSchema.Required("type", "id")
    data class ResourceAttribute(
        @JsonSchema.Type(JsonType.STRING)
        @JsonSchema.Pattern(PdpRequestValidation.RESOURCE_ATTRIBUTE_TYPE_PATTERN)
        @JsonSchema.Description(
            "The row's Type, for example `urn:kv:geonorge` or `urn:altinn:subresource`. It must be `urn:<name>` or " +
                "`urn:<name>:<name>`, where each name has only lowercase letters, `-` or `_`. The editor adds `urn:` " +
                "if the policy's author left it out, but allows other types than these, and a policy with one of " +
                "them cannot be asked about. Types under `urn:altinn:` are refused, other than " +
                "`urn:altinn:subresource`.",
        )
        val type: String? = null,

        @JsonSchema.Type(JsonType.STRING)
        @JsonSchema.Pattern(PdpRequestValidation.RESOURCE_ATTRIBUTE_ID_PATTERN)
        @JsonSchema.Description(
            "The row's Id, for example `dataset`. A policy made in the editor compares it as exact text, case included. " +
                "It cannot contain `:`, since the editor splits Type and Id at the last colon.",
        )
        val id: String? = null,
    )

    override fun toString(): String =
        "AuthorizeRequest(systemuserId=$systemuserId, pid=${pid?.let { "***********" }}, resourceId=$resourceId, " +
            "customerOrganizationNumber=$customerOrganizationNumber, action=$action, " +
            "resourceAttributes=$resourceAttributes)"

    fun parse(): Parsed {
        val attributes = resourceAttributes.orEmpty()
        val errors = PdpRequestValidation.validate(systemuserId, pid, resourceId, customerOrganizationNumber, action) +
            PdpRequestValidation.validateResourceAttributes(attributes.map { it.type to it.id })
        if (errors.isNotEmpty()) throw InvalidRequestException(errors)
        return Parsed(
            if (pid.isNullOrBlank()) SystemUserId.parse(systemuserId!!) else PersonId.parse(pid),
            ResourceId.parse(resourceId!!),
            OrganizationNumber.parse(customerOrganizationNumber!!),
            ActionId.parse(action!!),
            attributes.map { PdpResourceAttribute.parse(it.type!!, it.id!!) }.sortedBy { it.type },
        )
    }

    data class Parsed(
        val subject: PdpSubject,
        val resourceId: ResourceId,
        val customerOrganizationNumber: OrganizationNumber,
        val action: ActionId,
        val resourceAttributes: List<PdpResourceAttribute>,
    )
}
