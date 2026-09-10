package no.kartverket.altinnpdp.client.auth

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import no.kartverket.altinnpdp.client.support.NOW

class AccessTokenTest {

    private val leeway: Duration = Duration.ofSeconds(30)

    @Test
    fun `counts a token expiring exactly at the leeway boundary as expired`() {
        // The boundary is inclusive on purpose: a token that expires the moment the leeway runs
        // out is no use. Flipping this comparison would hand out tokens that die mid-request.
        assertTrue(AccessToken("t", NOW.plus(leeway)).isExpired(NOW, leeway))
    }

    @Test
    fun `keeps a token that outlives the leeway boundary by a second`() {
        assertFalse(AccessToken("t", NOW.plus(leeway).plusSeconds(1)).isExpired(NOW, leeway))
    }

    @Test
    fun `counts an already expired token as expired`() {
        assertTrue(AccessToken("t", NOW.minusSeconds(1)).isExpired(NOW, Duration.ZERO))
    }

    @Test
    fun `masks the token value in toString so it cannot reach the logs`() {
        val rendered = AccessToken("super-secret-jwt", NOW).toString()

        assertFalse(rendered.contains("super-secret-jwt"), "the token value must never be printed")
        assertTrue(rendered.contains("***"))
        assertTrue(rendered.contains(NOW.toString()), "the expiry is safe to print and useful in logs")
    }
}
