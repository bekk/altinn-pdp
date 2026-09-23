package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

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
