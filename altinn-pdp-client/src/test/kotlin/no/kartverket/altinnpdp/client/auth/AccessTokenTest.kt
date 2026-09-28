package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.support.NOW
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessTokenTest {

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
