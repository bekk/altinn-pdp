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
    description = "Simplified JSON REST API for the Altinn PDP",
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
            description = "The system user id from the Maskinporten token's authorization_details. Always a UUID. " +
                "Send either this or pid, not both.",
        )
    },
    "pid" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.PID_FORMAT.pattern,
            description = "The person's national identity number or D number, from the pid claim in their " +
                "Ansattporten token. Must have a valid date and check digits. Synthetic test persons, such as those " +
                "from Tenor, are only accepted against TT02. Send either this or systemuserId, not both.",
        )
    },
    "resourceId" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.RESOURCE_ID_FORMAT.pattern,
            description = "The resource's identifier in the Altinn Resource Registry. Lowercase letters, digits, " +
                "underscore and hyphen, at least 4 characters - the Resource Registry's own rule.",
        )
    },
    "customerOrganizationNumber" to {
        copy(
            type = JsonType.STRING,
            pattern = PdpRequestValidation.ORGANIZATION_NUMBER_FORMAT.pattern,
            description = "The customer: the organisation the system user or person acts on behalf of when " +
                "calling your API. For a system user it is authorization_details[].systemuser_org in the " +
                "Maskinporten token, NOT the consumer claim, which is the vendor's own org number. For a person it " +
                "is the organisation they chose when logging in, authorization_details[].authorized_parties[]" +
                ".orgno.ID in the Ansattporten token. A plain Norwegian org number, exactly 9 digits with a valid " +
                "MOD11 check digit. Strip the ISO6523 prefix: send \"311718371\", not \"0192:311718371\".",
        )
    },
    "action" to {
        copy(type = JsonType.STRING, description = "e.g. \"read\" or \"write\".")
    },
    required = listOf("resourceId", "customerOrganizationNumber", "action"),
)

private val authorizeResponseSchema = schemaInference.jsonSchema<AuthorizeResponse>().documented(
    "permit" to {
        copy(description = "Whether the request is permitted - shorthand for decision == \"PERMIT\".")
    },
    "decision" to { copy(description = "The underlying XACML decision.") },
    "status" to {
        copy(
            description = "Altinn's XACML status URN. \"...:status:ok\" means the request was evaluated; " +
                "\"...:status:processing-error\" means it could not be, which is how an unknown resourceId shows " +
                "up. Omitted when Altinn sends no status.",
        )
    },
    "minimumAuthenticationLevel" to {
        copy(
            description = "From the urn:altinn:minimum-authenticationlevel obligation. When present on a PERMIT, " +
                "the decision is conditional: the caller must confirm its own end user met at least this " +
                "authentication level before acting on the permit. This API cannot check it, as it never sees the " +
                "caller's token. Omitted when Altinn attaches no such obligation.",
        )
    },
)

private val errorResponseSchema = schemaInference.jsonSchema<ErrorResponse>().documented(
    "error" to {
        copy(description = "Human-readable summary. Not stable - branch on `code`, not on this.")
    },
    "code" to { copy(description = "Stable machine-readable code.") },
    "errors" to {
        copy(description = "Present when code is VALIDATION_ERROR. Every field that failed, not just the first.")
    },
    required = listOf("error", "code"),
)

internal val authorizeOperation: Operation.Builder.() -> Unit = {
    summary = "Check whether a system user or a person is authorized"
    description = "Asks the Altinn PDP whether the system user identified by [systemuserId], or the person " +
        "identified by [pid], may perform [action] on [resourceId] on behalf of the customer identified by " +
        "[customerOrganizationNumber]. Send exactly one of systemuserId and pid. The Altinn subscription key and " +
        "Maskinporten token are configured server-side; the caller never supplies them."

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
            description = "The PDP's decision."
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
                example("Deny", AuthorizeResponse(permit = false, decision = PdpDecision.DENY, status = OK_STATUS))
                example(
                    "NotEvaluated",
                    AuthorizeResponse(
                        permit = false,
                        decision = PdpDecision.INDETERMINATE,
                        status = PROCESSING_ERROR_STATUS,
                    ),
                )
            }
        }

        HttpStatusCode.BadRequest {
            description = "The request could not be understood. Validation failures carry code VALIDATION_ERROR " +
                "and list every failing field in \"errors\". A body that is not valid JSON, or has a field of the " +
                "wrong type, carries MALFORMED_BODY. Note that an unknown resourceId is not a 400: Altinn answers " +
                "200 with decision INDETERMINATE and a processing-error status, which this API passes through in " +
                "the \"status\" field."
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
            description = "The call to Altinn or Maskinporten failed for a reason unrelated to this request's " +
                "content - the PDP itself errored, or a token could not be obtained. The response never includes " +
                "Altinn's own error details; those are logged server-side instead."
            ContentType.Application.Json {
                schema = errorResponseSchema
                example("Example", ErrorResponse("The call to Altinn failed", ErrorCode.UPSTREAM_ERROR))
            }
        }

        HttpStatusCode.InternalServerError {
            description = "An unanticipated server error."
            ContentType.Application.Json {
                schema = errorResponseSchema
                example("Example", ErrorResponse("Internal server error", ErrorCode.INTERNAL_ERROR))
            }
        }
    }
}

private inline fun <reified T : Any> MediaType.Builder.example(name: String, value: T) =
    example(name, ExampleObject(value = GenericElement(exampleJson.encodeToJsonElement(value))))
