package no.kartverket.altinnpdp.restserver

import com.github.benmanes.caffeine.cache.Cache
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

    // Altinn may answer from any moment after the call starts, so the age counts from then.
    private class Load(val startedAt: Long, val result: CompletableFuture<PdpAuthorization>)

    private val cache: Cache<AuthorizeRequest.Parsed, Load> = Caffeine.newBuilder()
        .expireAfterWrite(timeToLive)
        .maximumSize(maximumSize)
        .ticker(ticker)
        .build()

    private val loadScope = CoroutineScope(SupervisorJob())

    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge {
        // Caffeine moves the expiry forward on every compute, so the time to live is enforced here instead.
        val acceptedAge = minOf(maxAge ?: defaultMaxAge, timeToLive)
        val pending = cache.asMap().compute(key) { _, cached ->
            cached?.takeIf { it.isYoungerThan(acceptedAge) } ?: start(load)
        }!!
        val authorization = try {
            pending.result.asDeferred().await()
        } catch (e: Exception) {
            if (pending.result.isCompletedExceptionally) forget(key, pending)
            throw e
        }
        if (authorization.decision == PdpDecision.INDETERMINATE) forget(key, pending)
        return AuthorizationWithAge(authorization, pending.age())
    }

    private fun start(load: suspend () -> PdpAuthorization): Load =
        Load(startedAt = ticker.read(), result = loadScope.future { load() })

    private fun Load.age(): Duration = Duration.ofNanos(ticker.read() - startedAt)

    // A load still in flight is joined only if it started recently enough, so max-age=0 always asks Altinn anew.
    private fun Load.isYoungerThan(age: Duration): Boolean = !result.isCompletedExceptionally && age() < age

    private fun forget(key: AuthorizeRequest.Parsed, pending: Load) {
        cache.asMap().remove(key, pending)
    }
}
