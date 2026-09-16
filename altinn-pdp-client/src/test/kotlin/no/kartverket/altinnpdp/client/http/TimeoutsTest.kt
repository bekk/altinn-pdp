package no.kartverket.altinnpdp.client.http

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeoutsTest {

    @Test
    fun `connect and request are separate values, not one shared default`() {
        assertEquals(Duration.ofSeconds(5), Timeouts.DEFAULT.connect)
        assertEquals(Duration.ofSeconds(10), Timeouts.DEFAULT.request)
    }

    @Test
    fun `the default budget is less than three request timeouts back to back`() {
        val backToBack = Timeouts.DEFAULT.request.multipliedBy(3)

        assertEquals(Duration.ofSeconds(20), Timeouts.DEFAULT.total)
        assertEquals(true, Timeouts.DEFAULT.total < backToBack)
    }

    @Test
    fun `overriding one value keeps the defaults for the rest`() {
        val timeouts = Timeouts(total = Duration.ofSeconds(3))

        assertEquals(Duration.ofSeconds(3), timeouts.total)
        assertEquals(Timeouts.DEFAULT.connect, timeouts.connect)
        assertEquals(Timeouts.DEFAULT.request, timeouts.request)
    }

    @Test
    fun `allows a total shorter than the request timeout`() {
        // Unvalidated on purpose: `Timeouts(total = ...)` alone already produces total < request.
        val timeouts = Timeouts(total = Duration.ofSeconds(1))

        assertEquals(Duration.ofSeconds(1), timeouts.total)
        assertEquals(Duration.ofSeconds(10), timeouts.request)
    }

    @Test
    fun `rejects a zero or negative timeout, naming the one at fault`() {
        val zero = assertFailsWith<IllegalArgumentException> { Timeouts(request = Duration.ZERO) }
        assertContains(zero.message!!, "request")

        val negativeConnect = assertFailsWith<IllegalArgumentException> {
            Timeouts(connect = Duration.ofSeconds(-1))
        }
        assertContains(negativeConnect.message!!, "connect")

        val negativeTotal = assertFailsWith<IllegalArgumentException> {
            Timeouts(total = Duration.ofSeconds(-1))
        }
        assertContains(negativeTotal.message!!, "total")
    }

    @Test
    fun `the client this library builds gets the connect timeout`() {
        val client = Http.defaultClient(Timeouts(connect = Duration.ofSeconds(2)))

        assertEquals(Duration.ofSeconds(2), client.connectTimeout().orElse(null))
    }

    @Test
    fun `a client built elsewhere has no connect timeout, so connecting falls back to request`() {
        val caller = java.net.http.HttpClient.newHttpClient()

        assertEquals(true, caller.connectTimeout().isEmpty)
    }
}
