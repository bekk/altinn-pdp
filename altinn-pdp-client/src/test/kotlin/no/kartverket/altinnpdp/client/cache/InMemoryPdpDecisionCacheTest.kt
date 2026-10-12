package no.kartverket.altinnpdp.client.cache

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
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryPdpDecisionCacheTest {

    private val timeToLive: Duration = Duration.ofMinutes(5)
    private val ticker = TestTicker()
    private val cache = InMemoryPdpDecisionCache(timeToLive, maximumSize = 100, ticker)
    private val loads = AtomicInteger()

    private val permit: suspend () -> PdpAuthorization = {
        loads.incrementAndGet()
        PdpAuthorization(PdpDecision.PERMIT)
    }

    @Test
    fun `serves the cached decision while it is fresh`() = runBlocking {
        repeat(3) { cache.get(key(), permit) }

        assertEquals(1, loads.get())
    }

    @Test
    fun `loads again once the decision has expired`() = runBlocking {
        cache.get(key(), permit)
        ticker.advance(timeToLive.minusNanos(1))
        cache.get(key(), permit)
        assertEquals(1, loads.get(), "one nanosecond left, so still cached")

        ticker.advance(Duration.ofNanos(1))
        cache.get(key(), permit)
        assertEquals(2, loads.get())
    }

    @Test
    fun `keeps a separate decision for each key`() = runBlocking {
        cache.get(key(action = "read"), permit)
        cache.get(key(action = "write"), permit)

        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep a failed load`() = runBlocking {
        assertFailsWith<PdpException> {
            cache.get(key()) { loads.incrementAndGet(); throw PdpException("Altinn is down", 503, "") }
        }
        cache.get(key(), permit)

        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep an indeterminate decision`() = runBlocking {
        val indeterminate = cache.get(key()) { loads.incrementAndGet(); PdpAuthorization(PdpDecision.INDETERMINATE) }
        cache.get(key(), permit)

        assertEquals(PdpDecision.INDETERMINATE, indeterminate.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `concurrent lookups of the same key share one load`() = runBlocking {
        coroutineScope {
            List(50) {
                async(Dispatchers.Default) {
                    cache.get(key()) {
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
    fun `a cancelled lookup does not cancel the load others are waiting on`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val load: suspend () -> PdpAuthorization = {
            loads.incrementAndGet()
            release.await()
            PdpAuthorization(PdpDecision.PERMIT)
        }

        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { cache.get(key(), load) }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(), load) }
        cancelled.cancelAndJoin()
        release.complete(Unit)

        assertEquals(PdpDecision.PERMIT, waiting.await().decision)
        assertEquals(1, loads.get())
    }

    private fun key(action: String = "read") = PdpDecisionKey(
        SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
        ResourceId.parse("test-resource"),
        OrganizationNumber.parse("923609016"),
        ActionId.parse(action),
    )

    private class TestTicker : Ticker {
        private var nanos = 0L

        override fun read(): Long = nanos

        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }
}
