package no.kartverket.altinnpdp.restserver.models

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    @JsonSchema.Description("Always `about:blank`. `code` says what went wrong.")
    val type: String,

    @JsonSchema.Description("The HTTP status text, for example `Bad Request`.")
    val title: String,

    @JsonSchema.Description("The HTTP status code, the same as the response's.")
    val status: Int,

    @JsonSchema.Description(
        "What went wrong, for people to read. The wording may change, so check `code` in your code.",
    )
    val detail: String,

    @JsonSchema.Description(
        """What went wrong. These values do not change, so your code can rely on them.

- `VALIDATION_ERROR` (400): one or more fields are missing or invalid. `errors` lists all of them.
- `MALFORMED_REQUEST` (400): the request could not be read, for example a body that is not valid JSON or a field
  with the wrong type.
- `INVALID_CACHE_CONTROL` (400): `max-age` in the `Cache-Control` header is not a whole number of seconds, 0 or more.
- `UPSTREAM_REJECTED` (400): the values passed this API's checks, but Altinn rejected them.
- `UPSTREAM_ERROR` (502): this API could not get an answer from Altinn. Your request did not cause
  it.
- `INTERNAL_ERROR` (500): an unexpected error in this API.""",
    )
    val code: ErrorCode,

    @JsonSchema.Type(JsonType.ARRAY)
    @JsonSchema.Description(
        "Only with `VALIDATION_ERROR`: every field that failed, so you can fix them all at once.",
    )
    val errors: List<FieldError>? = null,
) {
    companion object {
        fun of(status: HttpStatusCode, detail: String, code: ErrorCode, errors: List<FieldError>? = null) =
            ErrorResponse("about:blank", status.description, status.value, detail, code, errors)
    }
}
