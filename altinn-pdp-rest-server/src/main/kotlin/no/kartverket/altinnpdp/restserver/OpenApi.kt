package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ExampleObject
import io.ktor.openapi.GenericElement
import io.ktor.openapi.Headers
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.MediaType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.plus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.validation.ValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError
import no.kartverket.altinnpdp.restserver.models.ProblemType

private val openApiJson = Json { prettyPrint = true }

@OptIn(ExperimentalSerializationApi::class)
private val exampleJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val apiInfo = OpenApiInfo(
    title = "Altinn PDP REST API",
    version = "v0",
    description = """
        Asks Altinn whether a system user or a person may perform an action on an Altinn resource, on behalf of an
        organisation.

        The token your API receives says who the caller is. It does not say whether they have access to your
        resource. That is what this API answers.

        ## How to use it

        1. Validate the token your API received, as you do today. This API never sees that token.
        2. Take the subject and the organisation from the token. For a system user (Maskinporten token), send
           `systemuserId`. For a person (Ansattporten token), send `pid`. Either way, also send
           `customerOrganizationNumber`.
        3. Call `POST /authorize` with those, your `resourceId` and the `action`.
        4. Grant access only if `permit` is `true` and your end user meets `minimumAuthenticationLevel`, when the
           response has one.
        5. Treat everything else as no access, including every error response.

        Set your client timeout to at least 10 seconds. If Altinn is slow, you then get a `502` instead of a
        timeout of your own.

        ## Authentication level

        A `PERMIT` can come with a `minimumAuthenticationLevel`. The permit then only holds if your end user logged
        in at that level or higher. This API cannot check that, so you must. Your user's level is:

        | End user | Level |
        | :-- | :-- |
        | A system user, or any other Maskinporten token | 3 |
        | A person whose Ansattporten token has `acr` `substantial` | 3 |
        | A person whose Ansattporten token has `acr` `high` | 4 |

        If your user's level is lower than `minimumAuthenticationLevel`, treat the answer as no access.

        ## How fresh the answer is

        This API may answer from a cache instead of asking Altinn every time. A cached answer can still say `PERMIT`
        for a while after the access was removed in Altinn.

        Send `Cache-Control: max-age=<seconds>` to say how old an answer you accept. Send `max-age=0`, or `no-cache`,
        when you need Altinn's answer right now, for example before a write. Without the header, the server's default
        applies. The server also has an upper limit, and never answers with anything older, whatever `max-age` you send.

        The `Age` header in the answer says how many seconds ago this API asked Altinn for the decision. It is
        usually `0` when this call asked Altinn, and a little more when Altinn was slow to answer.
    """.trimIndent(),
)

fun Application.openApiSpec(): String =
    openApiJson.encodeToString(OpenApiDoc(info = apiInfo) + plugin(RoutingRoot).getAllRoutes())

internal val authorizeOperation: Operation.Builder.() -> Unit = {
    summary = "Check whether a system user or person has access"
    description = "Asks Altinn whether the system user (`systemuserId`) or person (`pid`) may perform `action` on " +
        "`resourceId`, on behalf of the organisation `customerOrganizationNumber`. Send exactly one of " +
        "`systemuserId` and `pid`."

    parameters {
        header(HttpHeaders.CacheControl) {
            description = "How old an answer you accept, as `max-age=<seconds>`. `max-age=0` or `no-cache` always " +
                "asks Altinn. Without it, the server's default applies. See How fresh the answer is in the API " +
                "description."
            schema = JsonSchema(type = JsonType.STRING)
            example = GenericElement("max-age=60")
        }
        header(HttpHeaders.XRequestId) {
            description = "An id to find this call by in this API's logs. Up to 128 ASCII letters, digits, `-`, `_`, " +
                "`.` and `:`. Without it, or with any other value, this API makes a new one. Every response has the id " +
                "in its `X-Request-Id` header."
            schema = JsonSchema(type = JsonType.STRING)
            example = GenericElement("3f2b8c1e-7d4a-4f0e-9b6a-2c5d8e1f4a7b")
        }
    }

    requestBody {
        required = true
        ContentType.Application.Json {
            schema = jsonSchema<AuthorizeRequest>()
            example(
                "SystemUser",
                AuthorizeRequest(
                    systemuserId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    resourceId = "altinn_access_management",
                    customerOrganizationNumber = "923609016",
                    action = "read",
                ),
            )
            example(
                "Person",
                AuthorizeRequest(
                    pid = "01817012309",
                    resourceId = "altinn_access_management",
                    customerOrganizationNumber = "923609016",
                    action = "read",
                ),
            )
        }
    }

    responses {
        HttpStatusCode.OK {
            description = "Altinn answered. This includes answers that deny access, so check `permit`."
            headers {
                header(HttpHeaders.Age) {
                    description = "How many seconds ago this API asked Altinn for the decision. Usually `0` when " +
                        "this call asked Altinn."
                    required = true
                    schema = JsonSchema(type = JsonType.INTEGER, minimum = 0.0)
                }
                requestId()
            }
            ContentType.Application.Json {
                schema = jsonSchema<AuthorizeResponse>()
                example(
                    "Permit",
                    AuthorizeResponse(
                        permit = true,
                        decision = PdpDecision.PERMIT,
                        status = AuthorizeResponse.OK_STATUS,
                        minimumAuthenticationLevel = 3,
                    ),
                )
                example(
                    "NoAccess",
                    AuthorizeResponse(permit = false, decision = PdpDecision.NOT_APPLICABLE, status = AuthorizeResponse.OK_STATUS),
                )
                example(
                    "UnknownResource",
                    AuthorizeResponse(
                        permit = false,
                        decision = PdpDecision.INDETERMINATE,
                        status = AuthorizeResponse.PROCESSING_ERROR_STATUS,
                    ),
                )
            }
        }

        HttpStatusCode.BadRequest {
            description = "The request was not accepted. `type` says why."
            headers { requestId() }
            ContentType.Application.ProblemJson {
                schema = jsonSchema<ErrorResponse>()
                example(
                    "MissingFields",
                    ErrorResponse.of(
                        ProblemType.VALIDATION_ERROR,
                        HttpStatusCode.BadRequest,
                        errors = listOf(
                            FieldError("systemuserId", ValidationCode.MISSING, "systemuserId or pid is required"),
                            FieldError("pid", ValidationCode.MISSING, "systemuserId or pid is required"),
                            FieldError("resourceId", ValidationCode.MISSING, "resourceId is required"),
                        ),
                    ),
                )
                example(
                    "BothSystemuserIdAndPid",
                    ErrorResponse.of(
                        ProblemType.VALIDATION_ERROR,
                        HttpStatusCode.BadRequest,
                        errors = listOf(
                            FieldError("systemuserId", ValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                            FieldError("pid", ValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                        ),
                    ),
                )
                example(
                    "InvalidCustomerOrganizationNumber",
                    ErrorResponse.of(
                        ProblemType.VALIDATION_ERROR,
                        HttpStatusCode.BadRequest,
                        errors = listOf(
                            FieldError(
                                field = "customerOrganizationNumber",
                                code = ValidationCode.INVALID_FORMAT,
                                message = "customerOrganizationNumber must have a valid MOD11 check digit",
                            ),
                        ),
                    ),
                )
                example("MalformedBody", ErrorResponse.of(ProblemType.MALFORMED_REQUEST, HttpStatusCode.BadRequest, "Malformed request body"))
                example(
                    "InvalidCacheControl",
                    ErrorResponse.of(
                        ProblemType.VALIDATION_ERROR,
                        HttpStatusCode.BadRequest,
                        errors = listOf(FieldError(HttpHeaders.CacheControl, ValidationCode.INVALID_FORMAT, "max-age in Cache-Control must be a whole number of seconds, 0 or more")),
                    ),
                )
                example("AltinnRejected", ErrorResponse.of(ProblemType.UPSTREAM_REJECTED, HttpStatusCode.BadRequest, "Altinn rejected the request"))
            }
        }

        HttpStatusCode.BadGateway {
            description = "This API could not get a decision from Altinn. Your request did not cause it. Treat it " +
                "as no access for now, and try again later."
            headers { requestId() }
            ContentType.Application.ProblemJson {
                schema = jsonSchema<ErrorResponse>()
                example("UpstreamError", ErrorResponse.of(ProblemType.UPSTREAM_ERROR, HttpStatusCode.BadGateway, "The call to Altinn failed"))
            }
        }

        HttpStatusCode.ServiceUnavailable {
            description = "The decision cache could not be read, and this API is set up to refuse calls rather than " +
                "ask Altinn without it. Your request did not cause it. Treat it as no access, and try again later."
            headers { requestId() }
            ContentType.Application.ProblemJson {
                schema = jsonSchema<ErrorResponse>()
                example(
                    "DecisionCacheUnavailable",
                    ErrorResponse.of(ProblemType.DECISION_CACHE_UNAVAILABLE, HttpStatusCode.ServiceUnavailable),
                )
            }
        }

        HttpStatusCode.InternalServerError {
            description = "An unexpected error in this API. Treat it as no access."
            headers { requestId() }
            ContentType.Application.ProblemJson {
                schema = jsonSchema<ErrorResponse>()
                example("InternalError", ErrorResponse.of(ProblemType.INTERNAL_ERROR, HttpStatusCode.InternalServerError))
            }
        }
    }
}

private fun Headers.Builder.requestId() = header(HttpHeaders.XRequestId) {
    description = "The id of this call in this API's logs: the `X-Request-Id` you sent, or a new one."
    required = true
    schema = JsonSchema(type = JsonType.STRING)
}

private inline fun <reified T : Any> MediaType.Builder.example(name: String, value: T) =
    example(name, ExampleObject(value = GenericElement(exampleJson.encodeToJsonElement(value))))
