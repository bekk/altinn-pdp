package no.kartverket.altinnpdp.restserver

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

class PdpTimeoutsTest {

    private val budgetPromisedToCallersInTheReadme: Duration = Duration.ofSeconds(10)

    @Test
    fun `connecting fits inside a request, and three requests back to back inside the response budget`() {
        assertTrue(CONNECT_TIMEOUT < REQUEST_TIMEOUT, "connecting should fit inside a request")
        assertTrue(
            REQUEST_TIMEOUT.multipliedBy(3) < budgetPromisedToCallersInTheReadme,
            "a lookup on cold caches makes three calls, which should fit inside what callers were told to allow",
        )
    }
}
