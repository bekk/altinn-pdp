package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.support.NOW
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessTokenTest {

    @Test
    fun `both token types reject blank credentials`() {
        assertFailsWith<IllegalArgumentException> { AltinnToken(" ", NOW) }
        assertFailsWith<IllegalArgumentException> { MaskinportenToken("", NOW) }
    }

    private val leeway: Duration = Duration.ofSeconds(30)

    @Test
    fun `counts a token expiring exactly at the leeway boundary as expired`() {
        assertTrue(AltinnToken("t", NOW.plus(leeway)).isExpired(NOW, leeway))
    }

    @Test
    fun `keeps a token that outlives the leeway boundary by a second`() {
        assertFalse(AltinnToken("t", NOW.plus(leeway).plusSeconds(1)).isExpired(NOW, leeway))
    }

    @Test
    fun `counts an already expired token as expired`() {
        assertTrue(AltinnToken("t", NOW.minusSeconds(1)).isExpired(NOW, Duration.ZERO))
    }

    @Test
    fun `masks the token value in toString so it cannot reach the logs`() {
        for (token in listOf(AltinnToken("super-secret-jwt", NOW), MaskinportenToken("super-secret-jwt", NOW))) {
            val rendered = token.toString()
            assertFalse(rendered.contains("super-secret-jwt"), "the token value must never be printed")
            assertTrue(rendered.contains("***"))
            assertTrue(rendered.contains(NOW.toString()), "the expiry is safe to print and useful in logs")
        }
    }
}
