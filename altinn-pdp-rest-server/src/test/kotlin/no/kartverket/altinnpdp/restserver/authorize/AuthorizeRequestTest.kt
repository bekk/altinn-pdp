package no.kartverket.altinnpdp.restserver.authorize

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

// Not SAMPLE_PID: synthetic numbers depend on the global ALLOW_SYNTHETIC_NUMBERS.
private const val PID = "01017012343"

class AuthorizeRequestTest {

    private val request = AuthorizeRequest(
        pid = PID,
        resourceId = "test-resource",
        customerOrganizationNumber = "923609016",
        action = "read",
        resourceAttributes = listOf(AuthorizeRequest.ResourceAttribute(type = "urn:kv:geonorge", id = "dataset")),
    )

    @Test
    fun `does not show the pid when printed`() {
        val rendered = request.toString()

        assertFalse(PID in rendered)
        assertContains(rendered, "pid=***********")
        assertContains(rendered, "resourceId=test-resource", message = "the other fields are safe to print")
    }

    @Test
    fun `does not show the pid inside a collection or an exception message`() {
        assertFalse(PID in listOf(request).toString())
        assertFalse(PID in IllegalStateException("Could not handle $request").stackTraceToString())
    }

    @Test
    fun `does not show the pid once parsed`() {
        assertFalse(PID in request.parse().toString())
    }
}
