package no.kartverket.altinnpdp.restserver.models

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    @JsonSchema.Description(
        """What went wrong, as a name that does not change, so your code can rely on it.

- `tag:kartverket.no,2026:validation-error` (400): one or more fields are missing or invalid. `errors` lists all of
  them.
- `tag:kartverket.no,2026:malformed-request` (400): the request could not be read, for example a body that is not
  valid JSON, a field with the wrong type, or a `Content-Type` that is not `application/json`.
- `tag:kartverket.no,2026:upstream-rejected` (400): the values passed this API's checks, but Altinn rejected them.
- `tag:kartverket.no,2026:not-found` (404): there is no endpoint at this path.
- `tag:kartverket.no,2026:method-not-allowed` (405): the endpoint at this path does not accept this HTTP method.
- `tag:kartverket.no,2026:upstream-error` (502): this API could not get a decision from Altinn. Your request did
  not cause it.
- `tag:kartverket.no,2026:decision-cache-unavailable` (503): the decision cache could not be read, and this API is
  set up to refuse calls rather than ask Altinn without it. Your request did not cause it.
- `tag:kartverket.no,2026:internal-error` (500): an unexpected error in this API.""",
    )
    val type: ProblemType,

    @JsonSchema.Description("A short name for the `type`, the same every time.")
    val title: String,

    @JsonSchema.Description("The HTTP status code, the same as the response's.")
    val status: Int,

    @JsonSchema.Description(
        "What went wrong this time, for people to read. Left out when `title` says it all. The wording may change, " +
            "so check `type` in your code.",
    )
    val detail: String? = null,

    @JsonSchema.Type(JsonType.ARRAY)
    @JsonSchema.Description(
        "Only with `tag:kartverket.no,2026:validation-error`: every field that failed, so you can fix them all at once.",
    )
    val errors: List<FieldError>? = null,
) {
    companion object {
        fun of(type: ProblemType, status: HttpStatusCode, detail: String? = null, errors: List<FieldError>? = null) =
            ErrorResponse(type, type.title, status.value, detail, errors)
    }
}
