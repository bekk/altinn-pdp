package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ExampleObject
import io.ktor.openapi.GenericElement
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
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError

private val openApiJson = Json { prettyPrint = true }

@OptIn(ExperimentalSerializationApi::class)
private val exampleJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val apiInfo = OpenApiInfo(
    title = "Altinn PDP REST API",
    version = "0.1.0",
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
    """.trimIndent(),
)

fun Application.openApiSpec(): String =
    openApiJson.encodeToString(OpenApiDoc(info = apiInfo) + plugin(RoutingRoot).getAllRoutes())

internal val authorizeOperation: Operation.Builder.() -> Unit = {
    summary = "Check whether a system user or person has access"
    description = "Asks Altinn whether the system user (`systemuserId`) or person (`pid`) may perform `action` on " +
        "`resourceId`, on behalf of the organisation `customerOrganizationNumber`. Send exactly one of " +
        "`systemuserId` and `pid`."

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
            description = "The request was not accepted. `code` says why."
            ContentType.Application.Json {
                schema = jsonSchema<ErrorResponse>()
                example(
                    "MissingFields",
                    ErrorResponse(
                        error = "Validation failed",
                        code = ErrorCode.VALIDATION_ERROR,
                        errors = listOf(
                            FieldError("systemuserId", PdpValidationCode.MISSING, "systemuserId or pid is required"),
                            FieldError("pid", PdpValidationCode.MISSING, "systemuserId or pid is required"),
                            FieldError("resourceId", PdpValidationCode.MISSING, "resourceId is required"),
                        ),
                    ),
                )
                example(
                    "BothSystemuserIdAndPid",
                    ErrorResponse(
                        error = "Validation failed",
                        code = ErrorCode.VALIDATION_ERROR,
                        errors = listOf(
                            FieldError("systemuserId", PdpValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                            FieldError("pid", PdpValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                        ),
                    ),
                )
                example(
                    "InvalidCustomerOrganizationNumber",
                    ErrorResponse(
                        error = "Validation failed",
                        code = ErrorCode.VALIDATION_ERROR,
                        errors = listOf(
                            FieldError(
                                field = "customerOrganizationNumber",
                                code = PdpValidationCode.INVALID_FORMAT,
                                message = "customerOrganizationNumber must have a valid MOD11 check digit",
                            ),
                        ),
                    ),
                )
                example("MalformedBody", ErrorResponse("Malformed request body", ErrorCode.MALFORMED_BODY))
                example("AltinnRejected", ErrorResponse("Altinn rejected the request", ErrorCode.UPSTREAM_REJECTED))
            }
        }

        HttpStatusCode.BadGateway {
            description = "This API could not get an answer from Altinn. Your request did not cause it. Treat it as " +
                "no access."
            ContentType.Application.Json {
                schema = jsonSchema<ErrorResponse>()
                example("UpstreamError", ErrorResponse("The call to Altinn failed", ErrorCode.UPSTREAM_ERROR))
            }
        }

        HttpStatusCode.InternalServerError {
            description = "An unexpected error in this API. Treat it as no access."
            ContentType.Application.Json {
                schema = jsonSchema<ErrorResponse>()
                example("InternalError", ErrorResponse("Internal server error", ErrorCode.INTERNAL_ERROR))
            }
        }
    }
}

private inline fun <reified T : Any> MediaType.Builder.example(name: String, value: T) =
    example(name, ExampleObject(value = GenericElement(exampleJson.encodeToJsonElement(value))))
