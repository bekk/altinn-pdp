package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import no.kartverket.altinnpdp.client.http.Timeouts
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
    fun `each default sits inside the one containing it, and all of them inside the response budget`() {
        val timeouts = timeoutsFromConfig(config())

        assertTrue(timeouts.request < timeouts.total, "request should be inside the total budget")
        assertTrue(
            timeouts.total < budgetPromisedToCallersInTheReadme,
            "the total budget should be inside what callers were told to allow",
        )
    }

    @Test
    fun `the server sets its own timeouts rather than inheriting the library defaults`() {
        val timeouts = timeoutsFromConfig(config())

        assertTrue(timeouts.total < Timeouts.DEFAULT_TOTAL)
    }

    @Test
    fun `a deployment can override both values`() {
        val timeouts = timeoutsFromConfig(
            config(
                "timeouts.requestMs" to "1500",
                "timeouts.totalMs" to "3000",
            )
        )

        assertEquals(Duration.ofMillis(1500), timeouts.request)
        assertEquals(Duration.ofMillis(3000), timeouts.total)
    }

    @Test
    fun `a blank override falls back to the default instead of failing`() {
        assertEquals(timeoutsFromConfig(config()).total, timeoutsFromConfig(config("timeouts.totalMs" to "  ")).total)
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
            timeoutsFromConfig(config("timeouts.totalMs" to "0"))
        }

        assertContains(e.message!!, "timeouts.totalMs")
    }
}
