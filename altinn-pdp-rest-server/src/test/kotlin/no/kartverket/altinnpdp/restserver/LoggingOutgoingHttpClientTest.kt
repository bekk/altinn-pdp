package no.kartverket.altinnpdp.restserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.support.response
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun authorizeThrough(altinn: OutgoingHttpClient) = runBlocking {
        testPdpClient(httpClient = LoggingOutgoingHttpClient(altinn)).authorize(
            SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
            ResourceId.parse("test-resource"),
            OrganizationNumber.parse("923609016"),
            ActionId.parse("read"),
        )
    }

    @Test
    fun `logs each call before it is sent and its status after, but not its headers or body`() {
        assertFailsWith<AltinnException> {
            authorizeThrough { request ->
                assertEquals("Calling ${request.method} ${request.url}", lines.last())
                when (request.url.path) {
                    "/token" -> response(200, "application/json", """{"access_token":"mp-token","expires_in":3600}""")
                    else -> response(500, "text/plain", "boom")
                }
            }
        }

        assertEquals(4, lines.size, "$lines")
        assertTrue(lines[1].startsWith("POST https://test.maskinporten.no/token answered 200 in "), lines[1])
        assertTrue(lines[3].startsWith("GET https://platform.tt02.altinn.no/authentication/api/v1/exchange/maskinporten answered 500 in "), lines[3])
        assertTrue(lines.none { "mp-token" in it || "assertion" in it }, "a header or body was logged: $lines")
    }

    @Test
    fun `logs a call that got no answer, and passes the failure on`() {
        assertFailsWith<MaskinportenException> {
            authorizeThrough { throw IOException("Connection refused") }
        }

        assertTrue(lines.last().startsWith("POST https://test.maskinporten.no/token failed after "), lines.last())
        assertContains(lines.last(), "Connection refused")
    }
}
