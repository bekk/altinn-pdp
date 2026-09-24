package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import java.time.Instant

/**
 * A token provider that never talks to anyone. It counts its calls, so a test can assert that the
 * client asks for a token as often as it should.
 */
internal class FakeTokenProvider(
    private val token: String = "altinn-token",
) : AltinnTokenProvider {

    var calls = 0
        private set

    override suspend fun getAltinnToken(): AccessToken {
        calls++
        return AccessToken(token, Instant.MAX)
    }
}
