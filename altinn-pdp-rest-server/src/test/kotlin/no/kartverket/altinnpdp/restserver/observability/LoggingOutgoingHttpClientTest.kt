package no.kartverket.altinnpdp.restserver.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingOutgoingHttpClientTest {

    private lateinit var appender: ListAppender<ILoggingEvent>

    private val outgoing get() = LoggerFactory.getLogger("outgoing") as Logger

    private val lines get() = appender.list.map { it.formattedMessage }

    @BeforeTest
    fun attachAppender() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        outgoing.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        outgoing.detachAppender(appender)
    }

    private val request = OutgoingRequest(
        method = HttpMethod.POST,
        url = URI("https://altinn.test/authorize"),
        headers = mapOf("Authorization" to "Bearer secret-token"),
        body = "secret-body".toByteArray(),
    )

    @Test
    fun `logs the call before it is sent and its status after, but not its headers or body`() = runBlocking {
        val client = LoggingOutgoingHttpClient {
            assertEquals(listOf("Calling POST https://altinn.test/authorize"), lines)
            response(200, "application/json", "{}")
        }

        client.send(request)

        assertEquals(2, lines.size, "$lines")
        assertTrue(lines[1].startsWith("POST https://altinn.test/authorize answered 200 in "), lines[1])
        assertTrue(lines.none { "secret" in it }, "a header or body was logged: $lines")
    }

    @Test
    fun `logs a call that got no answer, and passes the failure on`() {
        val failure = IOException("Connection refused")
        val client = LoggingOutgoingHttpClient { throw failure }

        assertSame(failure, assertFailsWith<IOException> { runBlocking { client.send(request) } })

        assertTrue(lines.last().startsWith("POST https://altinn.test/authorize failed after "), lines.last())
        assertContains(lines.last(), "Connection refused")
    }
}
