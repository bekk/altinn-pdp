package no.kartverket.altinnpdp.restserver

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import no.kartverket.altinnpdp.client.http.Timeouts

class PdpTimeoutsTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `each default sits inside the one containing it, and all of them inside the response budget`() {
        val timeouts = timeoutsFromEnv(env())

        assertTrue(timeouts.connect < timeouts.request, "connect should be inside request")
        assertTrue(timeouts.request < timeouts.total, "request should be inside the total budget")
        assertTrue(timeouts.total < RESPONSE_BUDGET, "the total budget should be inside what callers allow")
    }

    @Test
    fun `the server sets its own timeouts rather than inheriting the library defaults`() {
        val timeouts = timeoutsFromEnv(env())

        assertTrue(timeouts.total < Timeouts.DEFAULT_TOTAL)
    }

    @Test
    fun `a deployment can override every value`() {
        val timeouts = timeoutsFromEnv(
            env(
                "ALTINN_CONNECT_TIMEOUT_MS" to "500",
                "ALTINN_REQUEST_TIMEOUT_MS" to "1500",
                "ALTINN_TOTAL_TIMEOUT_MS" to "3000",
            )
        )

        assertEquals(Duration.ofMillis(500), timeouts.connect)
        assertEquals(Duration.ofMillis(1500), timeouts.request)
        assertEquals(Duration.ofMillis(3000), timeouts.total)
    }

    @Test
    fun `a blank override falls back to the default instead of failing`() {
        assertEquals(timeoutsFromEnv(env()).total, timeoutsFromEnv(env("ALTINN_TOTAL_TIMEOUT_MS" to "  ")).total)
    }

    @Test
    fun `an unparseable value fails at startup, naming the variable`() {
        val e = assertFailsWith<IllegalStateException> {
            timeoutsFromEnv(env("ALTINN_REQUEST_TIMEOUT_MS" to "4s"))
        }

        assertContains(e.message!!, "ALTINN_REQUEST_TIMEOUT_MS")
    }

    @Test
    fun `a non-positive value fails at startup, naming the variable`() {
        val e = assertFailsWith<IllegalArgumentException> {
            timeoutsFromEnv(env("ALTINN_CONNECT_TIMEOUT_MS" to "0"))
        }

        assertContains(e.message!!, "ALTINN_CONNECT_TIMEOUT_MS")
    }
}
