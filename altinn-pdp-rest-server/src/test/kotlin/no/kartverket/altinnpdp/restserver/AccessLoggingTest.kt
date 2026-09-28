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
    fun `the line has its own logger, so logback can route it apart from other logs`() {
        linesFor("true")
        val event = appender.list.single { it.formattedMessage.contains("/health/live") }

        assertEquals("access", event.loggerName)
    }

    @Test
    fun `logs nothing when switched off`() {
        val logged = linesFor("false")

        assertFalse(
            logged.any { it.contains("/health/live") },
            "expected no access log line, got: $logged",
        )
    }
}
