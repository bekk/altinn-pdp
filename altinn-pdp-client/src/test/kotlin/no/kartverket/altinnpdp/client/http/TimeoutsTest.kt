package no.kartverket.altinnpdp.client.http

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeoutsTest {

    @Test
    fun `the default budget is less than three request timeouts back to back`() {
        val backToBack = Timeouts.DEFAULT.request.multipliedBy(3)

        assertEquals(Duration.ofSeconds(10), Timeouts.DEFAULT.request)
        assertEquals(Duration.ofSeconds(20), Timeouts.DEFAULT.total)
        assertEquals(true, Timeouts.DEFAULT.total < backToBack)
    }

    @Test
    fun `overriding one value keeps the default for the other`() {
        val timeouts = Timeouts(total = Duration.ofSeconds(3))

        assertEquals(Duration.ofSeconds(3), timeouts.total)
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

        val negativeTotal = assertFailsWith<IllegalArgumentException> {
            Timeouts(total = Duration.ofSeconds(-1))
        }
        assertContains(negativeTotal.message!!, "total")
    }

    @Test
    fun `the client this library builds refuses to follow redirects`() {
        val client = Http.defaultClient()

        assertEquals(java.net.http.HttpClient.Redirect.NEVER, client.followRedirects())
    }
}
