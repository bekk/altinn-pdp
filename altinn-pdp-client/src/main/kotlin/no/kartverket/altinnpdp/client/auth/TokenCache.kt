package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

internal class TokenCache<T : AccessToken>(
    private val clock: Clock,
    private val refreshLeeway: Duration,
) {
    private val mutex = Mutex()
    private var token: T? = null

    suspend fun get(loader: suspend () -> T): T = mutex.withLock {
        val current = token
        if (current == null || current.isExpired(clock.instant(), refreshLeeway)) {
            loader().also { token = it }
        } else {
            current
        }
    }

    companion object {
        val DEFAULT_REFRESH_LEEWAY: Duration = Duration.ofSeconds(30)
    }
}
