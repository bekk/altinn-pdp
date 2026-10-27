package no.kartverket.altinnpdp.restserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.SAMPLE_PID
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.validation.ValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError
import no.kartverket.altinnpdp.restserver.models.ProblemType
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun `test health liveness endpoint`() = testServer {
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the generated spec as json`() = testServer {
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("/authorize"), spec.getValue("paths").jsonObject.keys)
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
    fun `a repeated authorize is answered from the decision cache`() {
        val sent = mutableListOf<OutgoingRequest>()
        authorizeTest(sent = sent, decisionCache = cacheForAMinute()) {
            val first = postAuthorize().authorizeResponse()
            val second = postAuthorize().authorizeResponse()

            assertEquals(first, second)
            assertEquals(1, sent.count { it.url.path == "/authorization/api/v1/authorize" })
        }
    }

    @Test
    fun `Cache-Control max-age=0 asks Altinn even when the decision is cached`() {
        val sent = mutableListOf<OutgoingRequest>()
        authorizeTest(sent = sent, decisionCache = cacheForAMinute()) {
            postAuthorize()
            val response = postAuthorize(cacheControl = "max-age=0")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, sent.count { it.url.path == "/authorization/api/v1/authorize" })
        }
    }

    @Test
    fun `an invalid max-age is rejected before Altinn is asked`() {
        val sent = mutableListOf<OutgoingRequest>()
        authorizeTest(sent = sent, decisionCache = cacheForAMinute()) {
            val response = postAuthorize(cacheControl = "max-age=-1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                listOf(FieldError(HttpHeaders.CacheControl, ValidationCode.INVALID_FORMAT, "max-age in Cache-Control must be a whole number of seconds, 0 or more")),
                response.errorResponse().errors,
            )
            assertEquals(emptyList(), sent)
        }
    }

    @Test
    fun `the Age header says how old the decision is, in whole seconds`() {
        val cache = object : PdpDecisionCache {
            override suspend fun get(
                key: AuthorizeRequest.Parsed,
                maxAge: Duration?,
                load: suspend () -> PdpAuthorization,
            ) = AuthorizationWithAge(load(), age = Duration.ofMillis(42_900))
        }
        authorizeTest(decisionCache = cache) {
            assertEquals("42", postAuthorize().headers[HttpHeaders.Age])
        }
    }

    @Test
    fun `without a cache the Age header is 0`() = authorizeTest {
        assertEquals("0", postAuthorize().headers[HttpHeaders.Age])
    }

    @Test
    fun `the decision cache is closed when the server stops`() {
        var closed = false
        val cache = object : PdpDecisionCache {
            override suspend fun get(
                key: AuthorizeRequest.Parsed,
                maxAge: Duration?,
                load: suspend () -> PdpAuthorization,
            ) = AuthorizationWithAge(load(), Duration.ZERO)

            override fun close() {
                closed = true
            }
        }
        authorizeTest(decisionCache = cache) { postAuthorize() }

        assertTrue(closed)
    }

    @Test
    fun `a decision cache that refuses the call answers 503`() {
        val cache = object : PdpDecisionCache {
            override suspend fun get(
                key: AuthorizeRequest.Parsed,
                maxAge: Duration?,
                load: suspend () -> PdpAuthorization,
            ): AuthorizationWithAge = throw DecisionCacheUnavailableException(IllegalStateException("Redis is down"))
        }
        authorizeTest(decisionCache = cache) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(ProblemType.DECISION_CACHE_UNAVAILABLE, response.errorResponse().type)
        }
    }

    @Test
    fun `the subject reaches Altinn as the attribute for its kind`() {
        val cases = mapOf(
            authorizeBody() to """{"attributeId":"urn:altinn:systemuser:uuid","value":"$SAMPLE_SYSTEMUSER_ID"}""",
            authorizeBody(systemuserId = null, pid = SAMPLE_PID) to
                """{"attributeId":"urn:altinn:person:identifier-no","value":"$SAMPLE_PID"}""",
        )
        for ((body, subject) in cases) {
            val sent = mutableListOf<OutgoingRequest>()
            authorizeTest(sent = sent) {
                assertEquals(HttpStatusCode.OK, postAuthorize(body).status, "for $body")
            }
            assertContains(sent.single { it.url.path == "/authorization/api/v1/authorize" }.body!!.decodeToString(), subject)
        }
    }

    @Test
    fun `a synthetic person is accepted against TT02, but only a real person in PROD`() {
        val synthetic = authorizeBody(systemuserId = null, pid = SAMPLE_PID)
        val real = authorizeBody(systemuserId = null, pid = "01017012343")

        authorizeTest(altinnEnvironment = AltinnEnvironment.TT02) {
            assertEquals(HttpStatusCode.OK, postAuthorize(synthetic).status)
        }
        authorizeTest(altinnEnvironment = AltinnEnvironment.PROD) {
            assertEquals(HttpStatusCode.OK, postAuthorize(real).status)

            val response = postAuthorize(synthetic)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                listOf(FieldError("pid", ValidationCode.INVALID_FORMAT, "pid must be a valid fødselsnummer or D number")),
                response.errorResponse().errors,
            )
        }
    }

    @Test
    fun `authorize passes the minimum authentication level through to the caller`() =
        authorizeTest(obligations = true) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AuthorizeResponse(
                    permit = true,
                    decision = PdpDecision.PERMIT,
                    status = OK_STATUS,
                    minimumAuthenticationLevel = 3,
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
                why = "neither systemuserId nor pid",
                body = authorizeBody(systemuserId = null),
                errors = listOf(
                    FieldError("systemuserId", ValidationCode.MISSING, "systemuserId or pid is required"),
                    FieldError("pid", ValidationCode.MISSING, "systemuserId or pid is required"),
                ),
            ),
            ValidationCase(
                why = "both systemuserId and pid",
                body = authorizeBody(pid = SAMPLE_PID),
                errors = listOf(
                    FieldError("systemuserId", ValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                    FieldError("pid", ValidationCode.CONFLICTING, "send systemuserId or pid, not both"),
                ),
            ),
            ValidationCase(
                why = "a pid with a bad check digit",
                body = authorizeBody(systemuserId = null, pid = "31827012312"),
                errors = listOf(FieldError("pid", ValidationCode.INVALID_FORMAT, "pid must be a valid fødselsnummer or D number")),
            ),
            ValidationCase(
                why = "an explicit null",
                body = """{"systemuserId":"$SAMPLE_SYSTEMUSER_ID","resourceId":"test-resource",""" +
                    """"customerOrganizationNumber":null,"action":"read"}""",
                errors = listOf(
                    FieldError("customerOrganizationNumber", ValidationCode.MISSING, "customerOrganizationNumber is required"),
                ),
            ),
            ValidationCase(
                why = "every field at once",
                body = """{"systemuserId":"nope","resourceId":"ab","customerOrganizationNumber":"12345"}""",
                errors = listOf(
                    FieldError("systemuserId", ValidationCode.INVALID_FORMAT, "systemuserId must be a UUID"),
                    FieldError(
                        "resourceId",
                        ValidationCode.INVALID_FORMAT,
                        "resourceId must be at least 4 characters of lowercase letters, digits, underscore or hyphen",
                    ),
                    FieldError(
                        "customerOrganizationNumber",
                        ValidationCode.INVALID_FORMAT,
                        "customerOrganizationNumber must be exactly 9 digits",
                    ),
                    FieldError("action", ValidationCode.MISSING, "action is required"),
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
    fun `a path with no endpoint gives 404, of type not-found`() = testServer {
        val response = client.get("/authorise")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(
            ErrorResponse.of(ProblemType.NOT_FOUND, HttpStatusCode.NotFound, "There is no endpoint at this path"),
            response.errorResponse(),
        )
    }

    @Test
    fun `a method the endpoint does not accept gives 405, of type method-not-allowed`() = testServer {
        val response = client.get("/authorize")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals(
            ErrorResponse.of(
                ProblemType.METHOD_NOT_ALLOWED,
                HttpStatusCode.MethodNotAllowed,
                "The endpoint at this path does not accept this method",
            ),
            response.errorResponse(),
        )
    }

    @Test
    fun `every error is sent as application problem+json`() {
        authorizeTest {
            assertEquals(ContentType.Application.ProblemJson, postAuthorize(malformedBody).contentType()?.withoutParameters())
        }
        authorizeTest(statusCode = 500) {
            assertEquals(ContentType.Application.ProblemJson, postAuthorize().contentType()?.withoutParameters())
        }
        testServer {
            for (response in listOf(client.get("/authorise"), client.get("/authorize"))) {
                assertEquals(ContentType.Application.ProblemJson, response.contentType()?.withoutParameters(), "${response.status}")
            }
        }
    }

    @Test
    fun `a malformed body never echoes the request or kotlinx's own advice back`() = authorizeTest {
        val response = postAuthorize(malformedBody)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val text = response.bodyAsText()
        assertEquals(
            ErrorResponse.of(ProblemType.MALFORMED_REQUEST, HttpStatusCode.BadRequest, "Malformed request body"),
            Json.decodeFromString(ErrorResponse.serializer(), text),
        )
        assertFalse(text.contains("coerceInputValues"), "leaks kotlinx advice: $text")
        assertFalse(text.contains("JSON input"), "echoes the caller's body: $text")
        assertFalse(text.contains("923609016"), "echoes the caller's values: $text")
    }

    @Test
    fun `a malformed body is never written to the log`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            authorizeTest {
                postAuthorize(malformedBody)
                postAuthorize(malformedPersonBody)
            }
        } finally {
            root.detachAppender(appender)
        }

        val logged = appender.list.flatMap { event ->
            listOf(event.formattedMessage) + generateSequence(event.throwableProxy) { it.cause }.map { it.message }
        }
        assertTrue(logged.any { it.startsWith("Malformed request body: ") }, "expected the warning, got: $logged")
        for (value in listOf(SAMPLE_SYSTEMUSER_ID, SAMPLE_PID)) {
            assertFalse(logged.any { it.orEmpty().contains(value) }, "logs the caller's body: $logged")
        }
    }

    @Test
    fun `the upstream status decides whether the caller or Altinn is to blame`() {
        val cases = listOf(
            UpstreamCase(400, HttpStatusCode.BadRequest, ProblemType.UPSTREAM_REJECTED, "only Altinn's own 400 is the caller's fault"),
            UpstreamCase(500, HttpStatusCode.BadGateway, ProblemType.UPSTREAM_ERROR, "anything else is not the caller's fault"),
        )

        for (case in cases) {
            authorizeTest(statusCode = case.upstream) {
                val response = postAuthorize()

                assertEquals(case.expected, response.status, case.describe())
                assertEquals(case.expectedType, response.errorResponse().type, case.describe())
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
                assertEquals(ProblemType.UPSTREAM_ERROR, response.errorResponse().type, "for $where")
            }
        }
    }

    @Test
    fun `an unexpected IllegalArgumentException is our fault, not the caller's`() =
        authorizeTest(failure = IllegalArgumentException("internal detail")) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                """{"type":"tag:kartverket.no,2026:internal-error","title":"Internal error","status":500}""",
                response.bodyAsText(),
            )
        }

    private val malformedBody = """{"systemuserId":"$SAMPLE_SYSTEMUSER_ID","resourceId":"test-resource",""" +
        """"customerOrganizationNumber":923609016,"action":"read"}"""

    private val malformedPersonBody = """{"pid":"$SAMPLE_PID","resourceId":"test-resource",""" +
        """"customerOrganizationNumber":923609016,"action":"read"}"""

    private fun cacheForAMinute() =
        InMemoryPdpDecisionCache(Duration.ofMinutes(1), defaultMaxAge = Duration.ofMinutes(1), maximumSize = 100)

    private data class ValidationCase(val why: String, val body: String, val errors: List<FieldError>)

    private data class UpstreamCase(
        val upstream: Int,
        val expected: HttpStatusCode,
        val expectedType: ProblemType,
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
                    ErrorResponse.of(ProblemType.VALIDATION_ERROR, HttpStatusCode.BadRequest, errors = case.errors),
                    response.errorResponse(),
                    "for ${case.why}",
                )
            }
        }
    }
}
