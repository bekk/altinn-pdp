package no.kartverket.altinnpdp.restserver.models

import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

@Serializable
data class FieldError(
    @JsonSchema.Description(
        "The request field or header, for example `customerOrganizationNumber`, `resourceAttributes[0].type` or " +
            "`Cache-Control`.",
    )
    val field: String,

    @JsonSchema.Description(
        """- `MISSING`: the field is absent, null or blank. If neither `systemuserId` nor `pid` is sent, both
  are listed.
- `INVALID_FORMAT`: the field is present but not valid.
- `CONFLICTING`: both `systemuserId` and `pid` were sent, or more than one entry in `resourceAttributes` has the
  same `type`. Every field in the conflict is listed.""",
    )
    val code: ValidationCode,

    @JsonSchema.Description("What is wrong, for people to read. The wording may change.")
    val message: String,
)

enum class ValidationCode {
    MISSING,
    INVALID_FORMAT,
    CONFLICTING,
}
