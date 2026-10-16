package no.kartverket.altinnpdp.restserver.models

import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.PdpDecision

@Serializable
data class AuthorizeResponse(
    @JsonSchema.Description(
        "`true` only when `decision` is `PERMIT`. Before granting access, also check " +
            "`minimumAuthenticationLevel`.",
    )
    val permit: Boolean,

    @JsonSchema.Description(
        """Altinn's answer:

- `PERMIT`: allowed. Check `minimumAuthenticationLevel` before granting access.
- `NOT_APPLICABLE`: no rule gives access. This is the usual answer when the caller has no access.
- `DENY`: a rule refuses access.
- `INDETERMINATE`: Altinn could not answer, for example because `resourceId` does not exist. See
  `status`.""",
    )
    val decision: PdpDecision,

    @JsonSchema.Type(JsonType.STRING)
    @JsonSchema.Description(
        """Tells "no access" apart from "could not answer". Both come with `permit` `false`.

- `$OK_STATUS`: Altinn evaluated the request.
- `$PROCESSING_ERROR_STATUS`: Altinn could not evaluate it, for example because
  `resourceId` does not exist. This points to a mistake in the request, not a refusal.

Not always present.""",
    )
    val status: String? = null,

    @JsonSchema.Type(JsonType.INTEGER)
    @JsonSchema.Description(
        "The lowest level your end user must have logged in with for a `PERMIT` to hold. See " +
            "Authentication level in the API description for how to find your user's level. Only present when " +
            "Altinn sets such a requirement.",
    )
    val minimumAuthenticationLevel: Int? = null,
) {
    companion object {
        const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"
        const val PROCESSING_ERROR_STATUS = "urn:oasis:names:tc:xacml:1.0:status:processing-error"
    }
}
