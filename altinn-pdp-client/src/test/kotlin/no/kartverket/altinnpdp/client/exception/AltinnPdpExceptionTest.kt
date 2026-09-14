package no.kartverket.altinnpdp.client.exception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The response body is what keeps an Altinn error diagnosable, and also what could dump an entire
 * HTML error page into the logs. These tests pin down the compromise between the two.
 */
class AltinnPdpExceptionTest {

    @Test
    fun `appends a short body to the message`() {
        assertEquals("boom: access denied", PdpException("boom", responseBody = "access denied").message)
    }

    @Test
    fun `leaves the message alone when there is no body`() {
        assertEquals("boom", PdpException("boom").message)
        assertEquals("boom", PdpException("boom", responseBody = "").message)
    }

    @Test
    fun `abbreviates a long body in the message but keeps it whole on the exception`() {
        val body = "x".repeat(600)

        val e = PdpException("boom", responseBody = body)

        assertTrue(e.message!!.startsWith("boom: " + "x".repeat(500)))
        assertTrue(e.message!!.endsWith("… (600 characters in total)"))
        assertEquals(body, e.responseBody, "the full body stays available for callers that want it")
    }

    @Test
    fun `carries the status code for callers that branch on it`() {
        val e = PdpException("boom", statusCode = 503, responseBody = "unavailable")

        assertEquals(503, e.statusCode)
        assertEquals("unavailable", e.responseBody)
    }
}
