package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.support.MutableClock
import no.kartverket.altinnpdp.client.support.NOW

class TokenCacheTest {

    private val leeway: Duration = Duration.ofSeconds(30)

    @Test
    fun `serves the cached token while it is fresh`() = runBlocking {
        val cache = TokenCache(MutableClock(), leeway)
        val loads = AtomicInteger()

        repeat(3) { cache.get { loads.incrementAndGet(); AccessToken("t", NOW.plusSeconds(300)) } }

        assertEquals(1, loads.get())
    }

    @Test
    fun `reloads once the cached token enters the refresh window`() = runBlocking {
        val clock = MutableClock()
        val cache = TokenCache(clock, leeway)
        val loads = AtomicInteger()
        val load: suspend () -> AccessToken = {
            AccessToken("token-${loads.incrementAndGet()}", clock.instant().plusSeconds(120))
        }

        assertEquals("token-1", cache.get(load).value)
        clock.advance(Duration.ofSeconds(89))
        assertEquals("token-1", cache.get(load).value, "still 31s of life left, outside the leeway")
        clock.advance(Duration.ofSeconds(1))
        assertEquals("token-2", cache.get(load).value, "exactly at the leeway boundary, so reload")
        assertEquals(2, loads.get())
    }

    @Test
    fun `invalidate drops a token that is still fresh`() = runBlocking {
        val cache = TokenCache(MutableClock(), leeway)
        val loads = AtomicInteger()
        val load: suspend () -> AccessToken = {
            AccessToken("token-${loads.incrementAndGet()}", NOW.plusSeconds(300))
        }

        assertEquals("token-1", cache.get(load).value)
        cache.invalidate()

        assertEquals("token-2", cache.get(load).value)
    }

    @Test
    fun `concurrent callers on a cold cache trigger exactly one load`() = runBlocking {
        // This is the whole reason TokenCache holds a Mutex across the network call rather than
        // just reading and writing a field. Simplify that away and fifty coroutines each fetch
        // their own token, which Maskinporten will rate-limit.
        val cache = TokenCache(MutableClock(), leeway)
        val loads = AtomicInteger()

        coroutineScope {
            List(50) {
                async(Dispatchers.Default) {
                    cache.get {
                        loads.incrementAndGet()
                        delay(20) // hold the lock long enough for the race to be real
                        AccessToken("t", NOW.plusSeconds(300))
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, loads.get())
    }
}
