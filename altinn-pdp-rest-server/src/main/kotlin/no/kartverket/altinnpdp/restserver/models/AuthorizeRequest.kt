package no.kartverket.altinnpdp.restserver.models

import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.restserver.InvalidRequestException
import no.kartverket.altinnpdp.restserver.PdpRequestValidation

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
) {
    fun parse(): Parsed {
        val errors = PdpRequestValidation.validate(systemuserId, pid, resourceId, customerOrganizationNumber, action)
        if (errors.isNotEmpty()) throw InvalidRequestException(errors)
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
