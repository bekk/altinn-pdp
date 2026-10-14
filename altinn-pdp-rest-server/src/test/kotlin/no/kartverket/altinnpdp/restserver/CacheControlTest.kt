package no.kartverket.altinnpdp.restserver

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CacheControlTest {

    @Test
    fun `no Cache-Control gives no max age`() {
        assertNull(headersOf().maxAge())
    }

    @Test
    fun `reads max-age as seconds`() {
        assertEquals(Duration.ofSeconds(30), cacheControl("max-age=30").maxAge())
    }

    @Test
    fun `no-cache means a max age of zero`() {
        assertEquals(Duration.ZERO, cacheControl("no-cache").maxAge())
    }

    @Test
    fun `directive names are case-insensitive`() {
        assertEquals(Duration.ofSeconds(30), cacheControl("Max-Age=30").maxAge())
    }

    @Test
    fun `ignores directives it does not use`() {
        assertNull(cacheControl("no-transform").maxAge())
        assertEquals(Duration.ofSeconds(30), cacheControl("no-transform, max-age=30").maxAge())
    }

    @Test
    fun `the strictest directive wins`() {
        assertEquals(Duration.ofSeconds(10), cacheControl("max-age=30, max-age=10").maxAge())
        assertEquals(Duration.ZERO, cacheControl("max-age=30", "no-cache").maxAge())
    }

    @Test
    fun `refuses a max-age that is not a whole number of seconds, 0 or more`() {
        for (directive in listOf("max-age", "max-age=", "max-age=ten", "max-age=-1", "max-age=1.5", "max-age=\"30\"")) {
            assertFailsWith<InvalidCacheControlException>(directive) { cacheControl(directive).maxAge() }
        }
    }

    private fun cacheControl(vararg values: String) = headersOf(HttpHeaders.CacheControl, values.toList())
}
