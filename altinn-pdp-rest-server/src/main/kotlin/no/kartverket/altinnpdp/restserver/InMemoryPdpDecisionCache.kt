package no.kartverket.altinnpdp.restserver

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import java.time.Duration

class InMemoryPdpDecisionCache internal constructor(
    timeToLive: Duration,
    private val defaultMaxAge: Duration,
    maximumSize: Long,
    private val ticker: Ticker,
) : PdpDecisionCache {

    constructor(timeToLive: Duration, defaultMaxAge: Duration, maximumSize: Long) :
        this(timeToLive, defaultMaxAge, maximumSize, Ticker.systemTicker())

    // Altinn may answer from any moment after the call starts, so the age counts from then.
    private class Loaded(val startedAt: Long, val authorization: PdpAuthorization)

    private val cache: Cache<AuthorizeRequest.Parsed, Loaded> = Caffeine.newBuilder()
        .expireAfterWrite(timeToLive)
        .maximumSize(maximumSize)
        .ticker(ticker)
        .build()

    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge {
        val cached = cache.getIfPresent(key)
        if (cached != null && cached.age() < (maxAge ?: defaultMaxAge)) return cached.withAge()

        // The cached decision is replaced only once the new one is in, so a failed load leaves it for other callers.
        val fresh = Loaded(startedAt = ticker.read(), authorization = load())
        if (fresh.authorization.decision != PdpDecision.INDETERMINATE) {
            if (cached == null) cache.asMap().putIfAbsent(key, fresh) else cache.asMap().replace(key, cached, fresh)
        }
        return fresh.withAge()
    }

    private fun Loaded.age(): Duration = Duration.ofNanos(ticker.read() - startedAt)

    private fun Loaded.withAge() = AuthorizationWithAge(authorization, age())
}
