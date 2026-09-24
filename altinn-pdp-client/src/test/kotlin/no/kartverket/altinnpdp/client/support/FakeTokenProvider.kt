package no.kartverket.altinnpdp.client.support

import kotlinx.coroutines.delay
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import java.time.Duration
import java.time.Instant

/**
 * A token provider that never talks to anyone. It counts its calls, so a test can assert that the
 * client asks for a token as often as it should, and it can be told to take its time.
 */
internal class FakeTokenProvider(
    private val token: String = "altinn-token",
    private val takes: Duration = Duration.ZERO,
) : AltinnTokenProvider {

    var calls = 0
        private set

    override suspend fun getAltinnToken(): AccessToken {
        calls++
        if (!takes.isZero) delay(takes.toMillis())
        return AccessToken(token, Instant.MAX)
    }
}
