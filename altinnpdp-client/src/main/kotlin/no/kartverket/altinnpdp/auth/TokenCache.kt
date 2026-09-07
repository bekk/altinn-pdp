package no.kartverket.altinnpdp.auth

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine-safe cache for a single token, refetched as it approaches expiry.
 *
 * Uses a [Mutex] rather than plain synchronization since [loader] suspends (it makes a network
 * call) - suspending while holding a JVM monitor is not something `synchronized` supports.
 */
internal class TokenCache(
    private val clock: Clock,
    private val refreshLeeway: Duration,
) {
    private val mutex = Mutex()
    private var token: AccessToken? = null

    suspend fun get(loader: suspend () -> AccessToken): AccessToken = mutex.withLock {
        val current = token
        if (current == null || current.isExpired(clock.instant(), refreshLeeway)) {
            loader().also { token = it }
        } else {
            current
        }
    }

    suspend fun invalidate() = mutex.withLock { token = null }
}
