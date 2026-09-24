package no.kartverket.altinnpdp.restserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessLoggingTest {

    private lateinit var appender: ListAppender<ILoggingEvent>

    private val root get() = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeTest
    fun attachAppender() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        root.detachAppender(appender)
    }

    private fun linesFor(enabled: String?) = run {
        testApplication {
            environment {
                config = if (enabled == null) {
                    MapApplicationConfig()
                } else {
                    MapApplicationConfig("accessLog.enabled" to enabled)
                }
            }
            application {
                configureAccessLogging()
                configureRouting()
            }
            client.get("/health/live")
        }
        appender.list.map { it.formattedMessage }
    }

    @Test
    fun `logs one line per request by default`() {
        val logged = linesFor(null)

        assertTrue(
            logged.any { it.contains("/health/live") },
            "expected an access log line for the request, got: $logged",
        )
    }

    @Test
    fun `the line carries status, method and duration`() {
        val line = linesFor("true").single { it.contains("/health/live") }

        assertTrue(line.contains("200"), "no status in: $line")
        assertTrue(line.contains("GET"), "no method in: $line")
        assertTrue(Regex("""\d+ms""").containsMatchIn(line), "no duration in: $line")
    }

    @Test
    fun `logs nothing when switched off`() {
        val logged = linesFor("false")

        assertFalse(
            logged.any { it.contains("/health/live") },
            "expected no access log line, got: $logged",
        )
    }

    @Test
    fun `enabled by default when the variable is unset`() {
        assertTrue(accessLogEnabled())
        assertTrue(accessLogEnabled(MapApplicationConfig("accessLog.enabled" to "")))
    }

    @Test
    fun `reads true and false`() {
        assertTrue(accessLogEnabled(MapApplicationConfig("accessLog.enabled" to "true")))
        assertFalse(accessLogEnabled(MapApplicationConfig("accessLog.enabled" to " false ")))
    }

    @Test
    fun `refuses a value that is neither true nor false`() {
        val e = assertFailsWith<IllegalStateException> {
            accessLogEnabled(MapApplicationConfig("accessLog.enabled" to "yes"))
        }

        assertEquals(true, e.message?.contains("must be true or false"), e.message)
    }
}
