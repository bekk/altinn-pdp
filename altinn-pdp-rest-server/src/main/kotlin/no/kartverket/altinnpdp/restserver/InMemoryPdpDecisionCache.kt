package no.kartverket.altinnpdp.restserver

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.future
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import java.time.Duration
import java.util.concurrent.CompletableFuture

class InMemoryPdpDecisionCache internal constructor(
    private val timeToLive: Duration,
    private val defaultMaxAge: Duration,
    maximumSize: Long,
    private val ticker: Ticker,
) : PdpDecisionCache {

    constructor(timeToLive: Duration, defaultMaxAge: Duration, maximumSize: Long) :
        this(timeToLive, defaultMaxAge, maximumSize, Ticker.systemTicker())

    private class Fetched(val authorization: PdpAuthorization, val fetchedAt: Long)

    private val cache: AsyncCache<AuthorizeRequest.Parsed, Fetched> = Caffeine.newBuilder()
        .expireAfterWrite(timeToLive)
        .maximumSize(maximumSize)
        .ticker(ticker)
        .buildAsync()

    private val loadScope = CoroutineScope(SupervisorJob())

    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): PdpAuthorization {
        // Caffeine moves the expiry forward on every compute, so the time to live is enforced here instead.
        val acceptedAge = minOf(maxAge ?: defaultMaxAge, timeToLive)
        val pending = cache.asMap().compute(key) { _, cached ->
            cached?.takeIf { it.isYoungerThan(acceptedAge) } ?: fetch(load)
        }!!
        val authorization = try {
            pending.asDeferred().await().authorization
        } catch (e: Exception) {
            if (pending.isCompletedExceptionally) forget(key, pending)
            throw e
        }
        if (authorization.decision == PdpDecision.INDETERMINATE) forget(key, pending)
        return authorization
    }

    private fun fetch(load: suspend () -> PdpAuthorization): CompletableFuture<Fetched> =
        loadScope.future { Fetched(load(), fetchedAt = ticker.read()) }

    // A load still in flight is as fresh as a decision can be, so every lookup may wait for it.
    private fun CompletableFuture<Fetched>.isYoungerThan(age: Duration): Boolean =
        !isDone || (!isCompletedExceptionally && ticker.read() - join().fetchedAt < age.toNanos())

    private fun forget(key: AuthorizeRequest.Parsed, pending: CompletableFuture<Fetched>) {
        cache.asMap().remove(key, pending)
    }
}
