package no.kartverket.altinnpdp.restserver.problem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import no.kartverket.altinnpdp.client.support.FakeAltinn
import no.kartverket.altinnpdp.client.support.SAMPLE_PID
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.restserver.authorizeTest
import no.kartverket.altinnpdp.restserver.errorResponse
import no.kartverket.altinnpdp.restserver.postAuthorize
import no.kartverket.altinnpdp.restserver.testServer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorHandlingTest {

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
    fun `authorize without a Content-Type header returns 400, not 500`() = authorizeTest {
        assertEquals(HttpStatusCode.BadRequest, postAuthorize(json = false).status)
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
}
