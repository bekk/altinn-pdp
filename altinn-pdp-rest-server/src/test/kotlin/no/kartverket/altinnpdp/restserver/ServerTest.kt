package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorCode
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
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the generated spec as json`() = testApplication {
        application {
            configureRouting()
        }
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(spec.getValue("paths").jsonObject.containsKey("/authorize"))
        assertFalse(spec.containsKey("servers"), "the host differs per environment, so the spec names none")
    }

    @Test
    fun `authorize returns Altinn's decision, and permit only for a Permit`() {
        val expected = mapOf(
            "Permit" to AuthorizeResponse(permit = true, decision = PdpDecision.PERMIT, status = OK_STATUS),
            "Deny" to AuthorizeResponse(permit = false, decision = PdpDecision.DENY, status = OK_STATUS),
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
                    decision = PdpDecision.PERMIT,
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
    fun `authorize reports every field it rejects, in one response`() {
        val cases = listOf(
            ValidationCase(
                why = "an absent field",
                body = authorizeBody(systemuserId = null),
                errors = listOf(FieldError("systemuserId", PdpValidationCode.MISSING, "systemuserId is required")),
            ),
            ValidationCase(
                why = "an explicit null",
                body = """{"systemuserId":"$SAMPLE_SYSTEMUSER_ID","resourceId":"test-resource",""" +
                    """"organizationNumber":null,"action":"read"}""",
                errors = listOf(
                    FieldError("organizationNumber", PdpValidationCode.MISSING, "organizationNumber is required"),
                ),
            ),
            ValidationCase(
                why = "every field at once",
                body = """{"systemuserId":"nope","resourceId":"ab","organizationNumber":"12345"}""",
                errors = listOf(
                    FieldError("systemuserId", PdpValidationCode.INVALID_FORMAT, "systemuserId must be a UUID"),
                    FieldError(
                        "resourceId",
                        PdpValidationCode.INVALID_FORMAT,
                        "resourceId must be at least 4 characters of lowercase letters, digits, underscore or hyphen",
                    ),
                    FieldError(
                        "organizationNumber",
                        PdpValidationCode.INVALID_FORMAT,
                        "organizationNumber must be exactly 9 digits",
                    ),
                    FieldError("action", PdpValidationCode.MISSING, "action is required"),
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
                """"organizationNumber":923609016,"action":"read"}""",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val text = response.bodyAsText()
        assertEquals(
            ErrorResponse("Malformed request body", ErrorCode.MALFORMED_BODY),
            Json.decodeFromString(ErrorResponse.serializer(), text),
        )
        assertFalse(text.contains("coerceInputValues"), "leaks kotlinx advice: $text")
        assertFalse(text.contains("JSON input"), "echoes the caller's body: $text")
        assertFalse(text.contains("923609016"), "echoes the caller's values: $text")
    }

    @Test
    fun `the upstream status decides whether the caller or Altinn is to blame`() {
        val cases = listOf(
            UpstreamCase(400, HttpStatusCode.BadRequest, ErrorCode.UPSTREAM_REJECTED, "only Altinn's own 400 is the caller's fault"),
            UpstreamCase(500, HttpStatusCode.BadGateway, ErrorCode.UPSTREAM_ERROR, "anything else is not the caller's fault"),
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
        for (where in listOf("Maskinporten", "the token exchange")) {
            authorizeTest(
                maskinportenStatus = if (where == "Maskinporten") 400 else 200,
                exchangeStatus = if (where == "the token exchange") 400 else 200,
            ) {
                val response = postAuthorize()

                assertEquals(HttpStatusCode.BadGateway, response.status, "for $where")
                assertEquals(ErrorCode.UPSTREAM_ERROR, response.errorResponse().code, "for $where")
            }
        }
    }

    @Test
    fun `an unexpected IllegalArgumentException is our fault, not the caller's`() =
        authorizeTest(failure = IllegalArgumentException("internal detail")) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(ErrorResponse("Internal server error", ErrorCode.INTERNAL_ERROR), response.errorResponse())
        }

    private data class ValidationCase(val why: String, val body: String, val errors: List<FieldError>)

    private data class UpstreamCase(
        val upstream: Int,
        val expected: HttpStatusCode,
        val expectedCode: ErrorCode,
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
                    ErrorResponse(error = "Validation failed", code = ErrorCode.VALIDATION_ERROR, errors = case.errors),
                    response.errorResponse(),
                    "for ${case.why}",
                )
            }
        }
    }
}
