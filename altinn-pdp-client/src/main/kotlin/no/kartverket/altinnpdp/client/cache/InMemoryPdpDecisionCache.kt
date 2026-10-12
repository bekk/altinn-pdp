package no.kartverket.altinnpdp.client.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.future
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import java.time.Duration
import java.util.concurrent.CompletableFuture

public class InMemoryPdpDecisionCache internal constructor(
    timeToLive: Duration,
    maximumSize: Long,
    ticker: Ticker,
) : PdpDecisionCache {

    public constructor(timeToLive: Duration, maximumSize: Long) : this(timeToLive, maximumSize, Ticker.systemTicker())

    private val cache: AsyncCache<PdpDecisionKey, PdpAuthorization> = Caffeine.newBuilder()
        .expireAfterWrite(timeToLive)
        .maximumSize(maximumSize)
        .ticker(ticker)
        .buildAsync()

    private val loadScope = CoroutineScope(SupervisorJob())

    override suspend fun get(key: PdpDecisionKey, load: suspend () -> PdpAuthorization): PdpAuthorization {
        val pending = cache.get(key) { _, _ -> loadScope.future { load() } }
        val authorization = try {
            pending.asDeferred().await()
        } catch (e: Exception) {
            if (pending.isCompletedExceptionally) forget(key, pending)
            throw e
        }
        if (authorization.decision == PdpDecision.INDETERMINATE) forget(key, pending)
        return authorization
    }

    private fun forget(key: PdpDecisionKey, pending: CompletableFuture<PdpAuthorization>) {
        cache.asMap().remove(key, pending)
    }
}
