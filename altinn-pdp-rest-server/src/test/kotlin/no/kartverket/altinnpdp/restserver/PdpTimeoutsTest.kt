package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdpTimeoutsTest {

    private fun config(vararg pairs: Pair<String, String>) = MapApplicationConfig(*pairs)

    private val budgetPromisedToCallersInTheReadme: Duration = Duration.ofSeconds(10)

    @Test
    fun `connecting fits inside a request, and three requests back to back inside the response budget`() {
        val timeouts = timeoutsFromConfig(config())

        assertTrue(timeouts.connect < timeouts.request, "connecting should fit inside a request")
        assertTrue(
            timeouts.request.multipliedBy(3) < budgetPromisedToCallersInTheReadme,
            "a lookup on cold caches makes three calls, which should fit inside what callers were told to allow",
        )
    }

    @Test
    fun `a deployment can override both values`() {
        val timeouts = timeoutsFromConfig(
            config(
                "timeouts.connectMs" to "1500",
                "timeouts.requestMs" to "3000",
            )
        )

        assertEquals(Duration.ofMillis(1500), timeouts.connect)
        assertEquals(Duration.ofMillis(3000), timeouts.request)
    }

    @Test
    fun `a blank override falls back to the default instead of failing`() {
        assertEquals(timeoutsFromConfig(config()).request, timeoutsFromConfig(config("timeouts.requestMs" to "  ")).request)
    }

    @Test
    fun `an unparseable value fails at startup, naming the property`() {
        val e = assertFailsWith<IllegalStateException> {
            timeoutsFromConfig(config("timeouts.requestMs" to "4s"))
        }

        assertContains(e.message!!, "timeouts.requestMs")
    }

    @Test
    fun `a non-positive value fails at startup, naming the property`() {
        val e = assertFailsWith<IllegalArgumentException> {
            timeoutsFromConfig(config("timeouts.connectMs" to "0"))
        }

        assertContains(e.message!!, "timeouts.connectMs")
    }
}
