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
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpStatus
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceAttribute
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.support.FakeAltinn
import no.kartverket.altinnpdp.client.support.SAMPLE_PID
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.support.SentAuthorization
import no.kartverket.altinnpdp.restserver.authorize.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.authorize.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.authorize.cache.AuthorizationWithAge
import no.kartverket.altinnpdp.restserver.authorize.cache.DecisionCacheUnavailableException
import no.kartverket.altinnpdp.restserver.authorize.cache.InMemoryPdpDecisionCache
import no.kartverket.altinnpdp.restserver.authorize.cache.PdpDecisionCache
import no.kartverket.altinnpdp.restserver.problem.ErrorResponse
import no.kartverket.altinnpdp.restserver.problem.FieldError
import no.kartverket.altinnpdp.restserver.problem.ProblemType
import no.kartverket.altinnpdp.restserver.problem.ValidationCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.Test
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

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = ["PERMIT | true", "DENY | false"])
    fun `authorize returns Altinn's decision, and permit only for a Permit`(decision: PdpDecision, permit: Boolean) {
        authorizeTest(altinn = FakeAltinn(decision = decision)) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AuthorizeResponse(permit = permit, decision = decision, status = OK_STATUS), response.authorizeResponse())
        }
    }

    @Test
    fun `a decision for one sub-resource is not answered from the cache for another`() {
        val altinn = FakeAltinn()
        authorizeTest(altinn = altinn, decisionCache = cacheForAMinute()) {
            postAuthorize()
            postAuthorize(authorizeBody(resourceAttributesJson = """[{"type":"urn:kv:geonorge","id":"dataset"}]"""))
            postAuthorize(authorizeBody(resourceAttributesJson = """[{"type":"urn:kv:geonorge","id":"service"}]"""))

            assertEquals(3, altinn.authorizations.size)
        }
    }

    @Test
    fun `the same resource attributes in another order are answered from the cache`() {
        val altinn = FakeAltinn()
        val metadata = """{"type":"urn:altinn:subresource","id":"metadata"}"""
        val service = """{"type":"urn:kv:geonorge","id":"service"}"""
        authorizeTest(altinn = altinn, decisionCache = cacheForAMinute()) {
            postAuthorize(authorizeBody(resourceAttributesJson = "[$metadata,$service]"))
            postAuthorize(authorizeBody(resourceAttributesJson = "[$service,$metadata]"))

            assertEquals(1, altinn.authorizations.size)
        }
    }

    @Test
    fun `Cache-Control max-age=0 asks Altinn even when the decision is cached`() {
        val altinn = FakeAltinn()
        authorizeTest(altinn = altinn, decisionCache = cacheForAMinute()) {
            postAuthorize()
            val response = postAuthorize(cacheControl = "max-age=0")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, altinn.authorizations.size)
        }
    }

    @Test
    fun `an invalid max-age is rejected before Altinn is asked`() {
        val altinn = FakeAltinn()
        authorizeTest(altinn = altinn, decisionCache = cacheForAMinute()) {
            val response = postAuthorize(cacheControl = "max-age=-1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                listOf(FieldError(HttpHeaders.CacheControl, ValidationCode.INVALID_FORMAT, "max-age in Cache-Control must be a whole number of seconds, 0 or more")),
                response.errorResponse().errors,
            )
            assertEquals(emptyList(), altinn.authorizations)
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `authorize asks Altinn about the subject, resource, customer and action it was sent`(
        kind: String,
        body: String,
        subject: PdpSubject,
    ) {
        val altinn = FakeAltinn()
        authorizeTest(altinn = altinn) {
            assertEquals(HttpStatusCode.OK, postAuthorize(body).status, kind)
        }
        assertEquals(
            SentAuthorization(
                subject = subject,
                resourceId = ResourceId.parse("test-resource"),
                customerOrganizationNumber = OrganizationNumber.parse("923609016"),
                action = ActionId.parse("read"),
                resourceAttributes = emptyList(),
            ),
            altinn.authorizations.single(),
        )
    }

    @Test
    fun `resource attributes are passed on to Altinn`() {
        val altinn = FakeAltinn()
        val body = authorizeBody(
            resourceAttributesJson = """[{"type":"urn:kv:geonorge","id":"service"},{"type":"urn:altinn:subresource","id":"metadata"}]""",
        )
        authorizeTest(altinn = altinn) {
            assertEquals(HttpStatusCode.OK, postAuthorize(body).status)
        }
        assertEquals(
            setOf(
                ResourceAttribute.parse("urn:kv:geonorge", "service"),
                ResourceAttribute.parse("urn:altinn:subresource", "metadata"),
            ),
            altinn.authorizations.single().resourceAttributes.toSet(),
        )
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
        authorizeTest(altinn = FakeAltinn(minimumAuthenticationLevel = 3)) {
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
    fun `minimumAuthenticationLevel is left out when Altinn sends no level`() = authorizeTest {
        val body = postAuthorize().bodyAsText()

        assertFalse(body.contains("minimumAuthenticationLevel"), "expected no minimumAuthenticationLevel in: $body")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRequestCases")
    fun `authorize reports every field it rejects, in one response`(case: ValidationCase) = assertRejected(case)

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
    fun `a malformed body never echoes the request or kotlinx's own advice back`() = authorizeTest {
        val response = postAuthorize(malformedBody)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse.of(ProblemType.MALFORMED_REQUEST, HttpStatusCode.BadRequest, "Malformed request body"),
            response.errorResponse(),
        )
        val text = response.bodyAsText()
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

    @ParameterizedTest(name = "upstream {0}: {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "400 | 400 | UPSTREAM_REJECTED | only Altinn's own 400 is the caller's fault",
            "500 | 502 | UPSTREAM_ERROR | anything else is not the caller's fault",
        ],
    )
    fun `the upstream status decides whether the caller or Altinn is to blame`(
        upstream: Int,
        expected: Int,
        expectedType: ProblemType,
        why: String,
    ) {
        authorizeTest(altinn = FakeAltinn(pdpStatus = upstream)) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.fromValue(expected), response.status, why)
            assertEquals(expectedType, response.errorResponse().type, why)
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["Maskinporten", "the token exchange"])
    fun `a 400 while fetching our own token is not blamed on the caller`(where: String) {
        val altinn = FakeAltinn(
            maskinportenStatus = if (where == "Maskinporten") 400 else 200,
            exchangeStatus = if (where == "the token exchange") 400 else 200,
        )
        authorizeTest(altinn = altinn) {
            val response = postAuthorize()

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(ProblemType.UPSTREAM_ERROR, response.errorResponse().type)
        }
    }

    @Test
    fun `an unexpected IllegalArgumentException is our fault, not the caller's`() =
        testServer(altinn = { throw IllegalArgumentException("internal detail") }) {
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

    data class ValidationCase(val why: String, val body: String, val errors: List<FieldError>) {
        override fun toString() = why
    }

    private fun assertRejected(case: ValidationCase) {
        authorizeTest {
            val response = postAuthorize(case.body)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                ErrorResponse.of(ProblemType.VALIDATION_ERROR, HttpStatusCode.BadRequest, errors = case.errors),
                response.errorResponse(),
            )
        }
    }

    @Test
    fun `authorize passes on a status Altinn sends that this API does not know`() =
        authorizeTest(
            altinn = FakeAltinn(
                decision = PdpDecision.INDETERMINATE,
                status = PdpStatus.Other("urn:oasis:names:tc:xacml:1.0:status:missing-attribute"),
            ),
        ) {
            assertEquals("urn:oasis:names:tc:xacml:1.0:status:missing-attribute", postAuthorize().authorizeResponse().status)
        }

    companion object {
        @JvmStatic
        fun subjects(): List<Arguments> = listOf(
            Arguments.of("a system user", authorizeBody(), SystemUserId.parse(SAMPLE_SYSTEMUSER_ID)),
            Arguments.of("a person", authorizeBody(systemuserId = null, pid = SAMPLE_PID), PersonId.parse(SAMPLE_PID)),
        )

        @JvmStatic
        fun rejectedRequestCases(): List<ValidationCase> = listOf(
            ValidationCase(
                why = "an invalid resource attribute next to a missing field",
                body = authorizeBody(action = null, resourceAttributesJson = """[{"type":"geonorge","id":"dataset"}]"""),
                errors = listOf(
                    FieldError("action", ValidationCode.MISSING, "action is required"),
                    FieldError(
                        "resourceAttributes[0].type",
                        ValidationCode.INVALID_FORMAT,
                        "resourceAttributes[0].type must be urn:<name> or urn:<name>:<name>, with names of lowercase letters, hyphen or underscore",
                    ),
                ),
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
    }
}
