package no.kartverket.altinnpdp.client.exception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `masks anything shaped like a pid in the body, on the message and the exception alike`() {
        val body = """{"error":"Invalid person-id 01817012309","party":"923609016","traceId":"018170123090"}"""
        val masked = """{"error":"Invalid person-id ***********","party":"923609016","traceId":"018170123090"}"""

        val e = PdpException("boom", responseBody = body)

        assertEquals(masked, e.responseBody)
        assertEquals("boom: $masked", e.message)
    }
}
