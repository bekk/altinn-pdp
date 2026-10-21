package no.kartverket.altinnpdp.restserver

import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryPdpDecisionCacheTest {

    private val timeToLive: Duration = Duration.ofMinutes(5)
    private val defaultMaxAge: Duration = Duration.ofMinutes(1)
    private val ticker = TestTicker()
    private val cache = InMemoryPdpDecisionCache(timeToLive, defaultMaxAge, maximumSize = 100, ticker)
    private val loads = AtomicInteger()

    private val permit: suspend () -> PdpAuthorization = {
        loads.incrementAndGet()
        PdpAuthorization(PdpDecision.PERMIT)
    }

    @Test
    fun `serves the cached decision while it is younger than the max age`() = runBlocking {
        val maxAge = Duration.ofSeconds(30)
        lookup(maxAge)
        ticker.advance(maxAge.minusNanos(1))
        lookup(maxAge)
        assertEquals(1, loads.get(), "one nanosecond left, so still cached")

        ticker.advance(Duration.ofNanos(1))
        lookup(maxAge)
        assertEquals(2, loads.get())
    }

    @Test
    fun `uses the default max age when the caller gives none`() = runBlocking {
        lookup()
        ticker.advance(defaultMaxAge.minusNanos(1))
        lookup()
        assertEquals(1, loads.get())

        ticker.advance(Duration.ofNanos(1))
        lookup()
        assertEquals(2, loads.get())
    }

    @Test
    fun `a max age of zero always loads`() = runBlocking {
        lookup()
        lookup(Duration.ZERO)
        lookup(Duration.ZERO)

        assertEquals(3, loads.get())
    }

    @Test
    fun `a max age above the time to live is capped to the time to live`() = runBlocking {
        val maxAge = timeToLive.multipliedBy(10)
        lookup(maxAge)
        ticker.advance(timeToLive.minusNanos(1))
        lookup(maxAge)
        assertEquals(1, loads.get())

        ticker.advance(Duration.ofNanos(1))
        lookup(maxAge)
        assertEquals(2, loads.get())
    }

    @Test
    fun `a decision loaded for one caller is served to the others`() = runBlocking {
        lookup()
        ticker.advance(Duration.ofSeconds(10))
        lookup(Duration.ZERO) { loads.incrementAndGet(); PdpAuthorization(PdpDecision.DENY) }

        assertEquals(PdpDecision.DENY, lookup().authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `reports how long ago the cached decision was loaded`() = runBlocking {
        assertEquals(Duration.ZERO, lookup().age)

        ticker.advance(Duration.ofSeconds(30))
        assertEquals(Duration.ofSeconds(30), lookup().age)
    }

    @Test
    fun `a reloaded decision starts again at age zero`() = runBlocking {
        lookup()
        ticker.advance(Duration.ofSeconds(30))

        assertEquals(Duration.ZERO, lookup(Duration.ZERO).age)
    }

    @Test
    fun `keeps a separate decision for each key`() = runBlocking {
        lookup(key = key(action = "read"))
        lookup(key = key(action = "write"))

        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep a failed load`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            lookup { loads.incrementAndGet(); error("Altinn is down") }
        }
        lookup()

        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep an indeterminate decision`() = runBlocking {
        val indeterminate = lookup { loads.incrementAndGet(); PdpAuthorization(PdpDecision.INDETERMINATE) }
        lookup()

        assertEquals(PdpDecision.INDETERMINATE, indeterminate.authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `a failed reload keeps the cached decision`() = runBlocking {
        lookup()
        ticker.advance(Duration.ofSeconds(10))
        assertFailsWith<IllegalStateException> {
            lookup(Duration.ZERO) { loads.incrementAndGet(); error("Altinn is down") }
        }

        assertEquals(Duration.ofSeconds(10), lookup().age)
        assertEquals(2, loads.get())
    }

    @Test
    fun `an indeterminate reload keeps the cached decision`() = runBlocking {
        lookup()
        lookup(Duration.ZERO) { loads.incrementAndGet(); PdpAuthorization(PdpDecision.INDETERMINATE) }

        assertEquals(PdpDecision.PERMIT, lookup().authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `callers that accept the cached decision do not wait for a reload`() = runBlocking {
        lookup()
        val release = CompletableDeferred<Unit>()
        val reload = async(start = CoroutineStart.UNDISPATCHED) {
            lookup(Duration.ZERO) { loads.incrementAndGet(); release.await(); PdpAuthorization(PdpDecision.DENY) }
        }

        assertEquals(PdpDecision.PERMIT, lookup().authorization.decision)
        release.complete(Unit)
        reload.await()
        assertEquals(PdpDecision.DENY, lookup().authorization.decision)
    }

    @Test
    fun `the age counts from when Altinn was asked, not from when it answered`() = runBlocking {
        val slow = lookup {
            loads.incrementAndGet()
            ticker.advance(Duration.ofSeconds(2))
            PdpAuthorization(PdpDecision.PERMIT)
        }

        assertEquals(Duration.ofSeconds(2), slow.age)
    }

    private suspend fun lookup(
        maxAge: Duration? = null,
        key: AuthorizeRequest.Parsed = key(),
        load: suspend () -> PdpAuthorization = permit,
    ): AuthorizationWithAge = cache.get(key, maxAge, load)

    private fun key(action: String = "read") = AuthorizeRequest(
        systemuserId = SAMPLE_SYSTEMUSER_ID,
        resourceId = "test-resource",
        customerOrganizationNumber = "923609016",
        action = action,
    ).parse()

    private class TestTicker : Ticker {
        private var nanos = 0L

        override fun read(): Long = nanos

        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }
}
