package no.kartverket.altinnpdp.client.auth

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
