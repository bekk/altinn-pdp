package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun `test health liveness endpoint`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the spec as json from the classpath`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.0.3", spec.getValue("openapi").jsonPrimitive.content)
        assertTrue(spec.getValue("paths").jsonObject.containsKey("/authorize"))
    }

    @Test
    fun `authorize returns every decision Altinn can answer with`() {
        val expected = mapOf(
            "Permit" to AuthorizeResponse(permit = true, decision = "PERMIT", status = OK_STATUS),
            "Deny" to AuthorizeResponse(permit = false, decision = "DENY", status = OK_STATUS),
            "NotApplicable" to AuthorizeResponse(permit = false, decision = "NOT_APPLICABLE", status = OK_STATUS),
            "Indeterminate" to AuthorizeResponse(permit = false, decision = "INDETERMINATE", status = OK_STATUS),
        )
        for ((decision, expectedResponse) in expected) {
            authorizeTest(decision = decision) {
                val response = postAuthorize()

                assertEquals(HttpStatusCode.OK, response.status, "for $decision")
                assertEquals(expectedResponse, response.authorizeResponse(), "for $decision")
            }
        }
    }

    @Test
    fun `authorize passes the minimum authentication levels through to the caller`() =
        authorizeTest(obligations = true) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AuthorizeResponse(
                    permit = true,
                    decision = "PERMIT",
                    status = OK_STATUS,
                    minimumAuthenticationLevel = 3,
                    minimumAuthenticationLevelOrg = 3,
                ),
                response.authorizeResponse(),
            )
        }

    @Test
    fun `the added fields are omitted when Altinn sends nothing for them`() = authorizeTest {
        val body = postAuthorize().bodyAsText()

        assertFalse(body.contains("minimumAuthenticationLevel"), "expected no level fields in: $body")
    }

    @Test
    fun `authorize reports every field it is missing, in one response`() {
        val cases = listOf(
            ValidationCase(
                why = "a blank field",
                body = authorizeBody(systemuserId = ""),
                errors = listOf(FieldError("systemuserId", "MISSING", "systemuserId is required")),
            ),
            ValidationCase(
                why = "one absent field",
                body = authorizeBody(systemuserId = null),
                errors = listOf(FieldError("systemuserId", "MISSING", "systemuserId is required")),
            ),
            ValidationCase(
                why = "several absent fields",
                body = authorizeBody(systemuserId = null, resourceId = null),
                errors = listOf(
                    FieldError("systemuserId", "MISSING", "systemuserId is required"),
                    FieldError("resourceId", "MISSING", "resourceId is required"),
                ),
            ),
            // An explicit null is a missing field, not malformed JSON.
            ValidationCase(
                why = "an explicit null",
                body = """{"systemuserId":"$SAMPLE_SYSTEMUSER_ID","resourceId":"test-resource",""" +
                    """"customerOrganizationNumber":null,"action":"read"}""",
                errors = listOf(
                    FieldError("customerOrganizationNumber", "MISSING", "customerOrganizationNumber is required"),
                ),
            ),
        )

        assertRejected(cases)
    }

    @Test
    fun `authorize reports every field whose value it rejects, in one response`() {
        val cases = listOf(
            ValidationCase(
                why = "an organization number that isn't 9 digits",
                body = authorizeBody(customerOrganizationNumber = "12345"),
                errors = listOf(
                    FieldError(
                        "customerOrganizationNumber",
                        "INVALID_FORMAT",
                        "customerOrganizationNumber must be exactly 9 digits",
                    ),
                ),
            ),
            // Rejected on the check digit alone, before Altinn is ever called.
            ValidationCase(
                why = "an organization number with a bad check digit",
                body = authorizeBody(customerOrganizationNumber = "123456789"),
                errors = listOf(
                    FieldError(
                        "customerOrganizationNumber",
                        "INVALID_FORMAT",
                        "customerOrganizationNumber must have a valid MOD11 check digit",
                    ),
                ),
            ),
            ValidationCase(
                why = "every field at once",
                body = """{"systemuserId":"nope","resourceId":"ab","customerOrganizationNumber":"12345"}""",
                errors = listOf(
                    FieldError("systemuserId", "INVALID_FORMAT", "systemuserId must be a UUID"),
                    FieldError(
                        "resourceId",
                        "INVALID_FORMAT",
                        "resourceId must be at least 4 characters of lowercase letters, digits, underscore or hyphen",
                    ),
                    FieldError(
                        "customerOrganizationNumber",
                        "INVALID_FORMAT",
                        "customerOrganizationNumber must be exactly 9 digits",
                    ),
                    FieldError("action", "MISSING", "action is required"),
                ),
            ),
        )

        assertRejected(cases)
    }

    @Test
    fun `authorize without a Content-Type header returns 400, not 500`() = authorizeTest {
        assertEquals(HttpStatusCode.BadRequest, postAuthorize(json = false).status)
    }

    @Test
    fun `a malformed body never echoes the request or kotlinx's own advice back`() = authorizeTest {
        val response = postAuthorize(
            """{"systemuserId":"$SAMPLE_SYSTEMUSER_ID","resourceId":"test-resource",""" +
                """"customerOrganizationNumber":923609016,"action":"read"}""",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val text = response.bodyAsText()
        assertEquals(
            ErrorResponse("Malformed request body", "MALFORMED_BODY"),
            Json.decodeFromString(ErrorResponse.serializer(), text),
        )
        assertFalse(text.contains("coerceInputValues"), "leaks kotlinx advice: $text")
        assertFalse(text.contains("JSON input"), "echoes the caller's body: $text")
        assertFalse(text.contains("923609016"), "echoes the caller's values: $text")
    }

    @Test
    fun `the upstream status decides whether the caller or Altinn is to blame`() {
        val cases = listOf(
            UpstreamCase(400, HttpStatusCode.BadRequest, "UPSTREAM_REJECTED", "only Altinn's own 400 is the caller's fault"),
            UpstreamCase(401, HttpStatusCode.BadGateway, "UPSTREAM_ERROR", "our own credentials are not the caller's fault"),
            UpstreamCase(403, HttpStatusCode.BadGateway, "UPSTREAM_ERROR", "our own credentials are not the caller's fault"),
            UpstreamCase(429, HttpStatusCode.BadGateway, "UPSTREAM_ERROR", "our own quota is not the caller's fault"),
            UpstreamCase(500, HttpStatusCode.BadGateway, "UPSTREAM_ERROR", "a PDP server failure is not the caller's fault"),
        )

        for (case in cases) {
            authorizeTest(statusCode = case.upstream) {
                val response = postAuthorize()

                assertEquals(case.expected, response.status, case.describe())
                assertEquals(case.expectedCode, response.errorResponse().code, case.describe())
            }
        }
    }

    @Test
    fun `a 400 while fetching our own token is not blamed on the caller`() {
        val failures = listOf(
            MaskinportenException("Maskinporten responded 400", statusCode = 400, responseBody = """{"error":"invalid_grant"}"""),
            AltinnException("Altinn responded 400 to the token exchange", statusCode = 400),
        )

        for (failure in failures) {
            authorizeTest(tokenProvider = failingTokenProvider(failure)) {
                val response = postAuthorize()

                assertEquals(HttpStatusCode.BadGateway, response.status, "for ${failure::class.simpleName}")
                assertEquals("UPSTREAM_ERROR", response.errorResponse().code, "for ${failure::class.simpleName}")
            }
        }
    }

    private data class ValidationCase(val why: String, val body: String, val errors: List<FieldError>)

    private data class UpstreamCase(
        val upstream: Int,
        val expected: HttpStatusCode,
        val expectedCode: String,
        val why: String,
    ) {
        fun describe() = "upstream $upstream: $why"
    }

    private fun assertRejected(cases: List<ValidationCase>) {
        for (case in cases) {
            authorizeTest {
                val response = postAuthorize(case.body)

                assertEquals(HttpStatusCode.BadRequest, response.status, "for ${case.why}")
                assertEquals(
                    ErrorResponse(error = "Validation failed", code = "VALIDATION_ERROR", errors = case.errors),
                    response.errorResponse(),
                    "for ${case.why}",
                )
            }
        }
    }
}
