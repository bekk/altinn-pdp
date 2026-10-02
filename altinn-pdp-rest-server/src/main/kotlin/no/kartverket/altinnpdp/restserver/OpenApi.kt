package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ExampleObject
import io.ktor.openapi.GenericElement
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.KotlinxSerializerJsonSchemaInference
import io.ktor.openapi.MediaType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.plus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.EmptySerializersModule
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError

private val openApiJson = Json { prettyPrint = true }

private val schemaInference = KotlinxSerializerJsonSchemaInference(EmptySerializersModule())

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

private const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"

private const val PROCESSING_ERROR_STATUS = "urn:oasis:names:tc:xacml:1.0:status:processing-error"

fun Application.openApiSpec(): String =
    openApiJson.encodeToString(OpenApiDoc(info = apiInfo) + plugin(RoutingRoot).getAllRoutes())

object OpenApiSpecFile {

    const val NAME: String = "openapi.json"

    fun contentsFor(servedSpec: String): String = servedSpec + "\n"
}

private fun JsonSchema.documented(
    vararg fields: Pair<String, JsonSchema.() -> JsonSchema>,
    required: List<String>? = this.required,
): JsonSchema {
    val documentation = fields.toMap()
    val unknown = (documentation.keys + required.orEmpty()) - properties?.keys.orEmpty()
    require(unknown.isEmpty()) {
        "$title has no ${unknown.joinToString()} - a renamed field leaves the spec describing one that is gone"
    }
    return copy(
        required = required,
        properties = properties?.mapValues { (field, schema) ->
            documentation[field]?.let { schema.mapValue(it) } ?: schema
        },
    )
}

private val authorizeRequestSchema = schemaInference.jsonSchema<AuthorizeRequest>().documented(
    "systemuserId" to {
        copy(
            type = JsonType.STRING,
            format = "uuid",
            description = "The system user, when your caller uses one. In the Maskinporten token it is the UUID in " +
                "`authorization_details[].systemuser_id`. Send either this or `pid`, not both.",
        )
    },
    "pid" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.PID_FORMAT.pattern,
            description = "The person, when your caller is logged in as one: their national identity number or D " +
                "number, from the `pid` claim in the Ansattporten token. It must have a valid date and check " +
                "digits. In test, synthetic persons such as those from Tenor are accepted too. In production, " +
                "only real persons are. Send either this or `systemuserId`, not both.",
        )
    },
    "resourceId" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.RESOURCE_ID_FORMAT.pattern,
            description = "Your resource's id in the Altinn Resource Registry, for example " +
                "`altinn_access_management`. At least 4 characters, using lowercase letters, digits, underscore " +
                "and hyphen. An id that does not exist is not rejected with a `400`. You get a `200` with " +
                "`decision` `INDETERMINATE` instead.",
        )
    },
    "customerOrganizationNumber" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.ORGANIZATION_NUMBER_FORMAT.pattern,
            description = """
                The customer: the organisation the system user or person acts on behalf of when calling your API.

                - For a system user, it is the org number in `authorization_details[].systemuser_org` in the
                  Maskinporten token. Do not use the `consumer` claim. That is the vendor's own org number.
                - For a person, it is the organisation they chose when logging in, in
                  `authorization_details[].authorized_parties[].orgno` in the Ansattporten token.

                Send the 9 digits without the `0192:` prefix: `311718371`, not `0192:311718371`. The last digit
                must be a valid check digit.
            """.trimIndent(),
        )
    },
    "action" to {
        copy(
            type = JsonType.STRING,
            description = "What the caller wants to do, as named in your resource's policy, for example `read` or " +
                "`write`. Any value that is not empty is accepted.",
        )
    },
    required = listOf("resourceId", "customerOrganizationNumber", "action"),
)

private val authorizeResponseSchema = schemaInference.jsonSchema<AuthorizeResponse>().documented(
    "permit" to {
        copy(
            description = "`true` only when `decision` is `PERMIT`. Before granting access, also check " +
                "`minimumAuthenticationLevel`.",
        )
    },
    "decision" to {
        copy(
            description = """
                Altinn's answer:

                - `PERMIT`: allowed. Check `minimumAuthenticationLevel` before granting access.
                - `NOT_APPLICABLE`: no rule gives access. This is the usual answer when the caller has no access.
                - `DENY`: a rule refuses access.
                - `INDETERMINATE`: Altinn could not answer, for example because `resourceId` does not exist. See
                  `status`.
            """.trimIndent(),
        )
    },
    "status" to {
        copy(
            type = JsonType.STRING,
            description = """
                Tells "no access" apart from "could not answer". Both come with `permit` `false`.

                - `$OK_STATUS`: Altinn evaluated the request.
                - `$PROCESSING_ERROR_STATUS`: Altinn could not evaluate it, for example because
                  `resourceId` does not exist. This points to a mistake in the request, not a refusal.

                Not always present.
            """.trimIndent(),
        )
    },
    "minimumAuthenticationLevel" to {
        copy(
            type = JsonType.INTEGER,
            description = "The lowest level your end user must have logged in with for a `PERMIT` to hold. See " +
                "Authentication level in the API description for how to find your user's level. Only present when " +
                "Altinn sets such a requirement.",
        )
    },
)

private val fieldErrorSchema = schemaInference.jsonSchema<FieldError>().documented(
    "field" to { copy(description = "The request field, for example `customerOrganizationNumber`.") },
    "code" to {
        copy(
            description = """
                - `MISSING`: the field is absent, null or blank. If neither `systemuserId` nor `pid` is sent, both
                  are listed.
                - `INVALID_FORMAT`: the field is present but not valid.
                - `CONFLICTING`: both `systemuserId` and `pid` were sent. Both are listed.
            """.trimIndent(),
        )
    },
    "message" to { copy(description = "What is wrong, for people to read. The wording may change.") },
)

private val errorResponseSchema = schemaInference.jsonSchema<ErrorResponse>().documented(
    "error" to {
        copy(description = "A short summary for people to read. The wording may change, so check `code` in your code.")
    },
    "code" to {
        copy(
            description = """
                What went wrong. These values do not change, so your code can rely on them.

                - `VALIDATION_ERROR` (400): one or more fields are missing or invalid. `errors` lists all of them.
                - `MALFORMED_BODY` (400): the body is not valid JSON, or a field has the wrong type.
                - `UPSTREAM_REJECTED` (400): the values passed this API's checks, but Altinn rejected them.
                - `UPSTREAM_ERROR` (502): this API could not get an answer from Altinn. Your request did not cause
                  it.
                - `INTERNAL_ERROR` (500): an unexpected error in this API.
            """.trimIndent(),
        )
    },
    "errors" to {
        copy(
            type = JsonType.ARRAY,
            items = ReferenceOr.Value(fieldErrorSchema),
            description = "Only with `VALIDATION_ERROR`: every field that failed, so you can fix them all at once.",
        )
    },
    required = listOf("error", "code"),
)

internal val authorizeOperation: Operation.Builder.() -> Unit = {
    summary = "Check whether a system user or person has access"
    description = "Asks Altinn whether the system user (`systemuserId`) or person (`pid`) may perform `action` on " +
        "`resourceId`, on behalf of the organisation `customerOrganizationNumber`. Send exactly one of " +
        "`systemuserId` and `pid`."

    requestBody {
        required = true
        ContentType.Application.Json {
            schema = authorizeRequestSchema
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
                schema = authorizeResponseSchema
                example(
                    "Permit",
                    AuthorizeResponse(
                        permit = true,
                        decision = PdpDecision.PERMIT,
                        status = OK_STATUS,
                        minimumAuthenticationLevel = 3,
                    ),
                )
                example(
                    "NoAccess",
                    AuthorizeResponse(permit = false, decision = PdpDecision.NOT_APPLICABLE, status = OK_STATUS),
                )
                example(
                    "UnknownResource",
                    AuthorizeResponse(
                        permit = false,
                        decision = PdpDecision.INDETERMINATE,
                        status = PROCESSING_ERROR_STATUS,
                    ),
                )
            }
        }

        HttpStatusCode.BadRequest {
            description = "The request was not accepted. `code` says why."
            ContentType.Application.Json {
                schema = errorResponseSchema
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
                schema = errorResponseSchema
                example("UpstreamError", ErrorResponse("The call to Altinn failed", ErrorCode.UPSTREAM_ERROR))
            }
        }

        HttpStatusCode.InternalServerError {
            description = "An unexpected error in this API. Treat it as no access."
            ContentType.Application.Json {
                schema = errorResponseSchema
                example("InternalError", ErrorResponse("Internal server error", ErrorCode.INTERNAL_ERROR))
            }
        }
    }
}

private inline fun <reified T : Any> MediaType.Builder.example(name: String, value: T) =
    example(name, ExampleObject(value = GenericElement(exampleJson.encodeToJsonElement(value))))
