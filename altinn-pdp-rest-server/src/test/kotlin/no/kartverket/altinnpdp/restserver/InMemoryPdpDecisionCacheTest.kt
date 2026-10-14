package no.kartverket.altinnpdp.restserver

import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        assertEquals(PdpDecision.DENY, lookup().decision)
        assertEquals(2, loads.get())
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

        assertEquals(PdpDecision.INDETERMINATE, indeterminate.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `concurrent lookups of the same key share one load`() = runBlocking {
        coroutineScope {
            List(50) {
                async(Dispatchers.Default) {
                    lookup {
                        loads.incrementAndGet()
                        delay(20)
                        PdpAuthorization(PdpDecision.PERMIT)
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, loads.get())
    }

    @Test
    fun `a lookup with max age zero waits for the load already in flight`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            lookup { loads.incrementAndGet(); release.await(); PdpAuthorization(PdpDecision.PERMIT) }
        }
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { lookup(Duration.ZERO) }
        release.complete(Unit)
        awaitAll(inFlight, fresh)

        assertEquals(1, loads.get())
    }

    @Test
    fun `a cancelled lookup does not cancel the load others are waiting on`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val load: suspend () -> PdpAuthorization = {
            loads.incrementAndGet()
            release.await()
            PdpAuthorization(PdpDecision.PERMIT)
        }

        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { lookup(load = load) }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { lookup(load = load) }
        cancelled.cancelAndJoin()
        release.complete(Unit)

        assertEquals(PdpDecision.PERMIT, waiting.await().decision)
        assertEquals(1, loads.get())
    }

    private suspend fun lookup(
        maxAge: Duration? = null,
        key: AuthorizeRequest.Parsed = key(),
        load: suspend () -> PdpAuthorization = permit,
    ): PdpAuthorization = cache.get(key, maxAge, load)

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
