package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.support.NOW
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessTokenTest {

    @Test
    fun `does not show the token when printed`() {
        val token = AccessToken.of("secret-token")

        assertFalse("secret-token" in "Calling with $token")
        assertFalse("secret-token" in listOf(token).toString(), "nor inside a collection")
    }

    @Test
    fun `masks the token value of the internal tokens with an expiry`() {
        for (token in listOf(AltinnToken("super-secret-jwt", NOW), MaskinportenToken("super-secret-jwt", NOW))) {
            val rendered = token.toString()
            assertFalse(rendered.contains("super-secret-jwt"), "the token value must never be printed")
            assertTrue(rendered.contains("***"))
            assertTrue(rendered.contains(NOW.toString()), "the expiry is safe to print and useful in logs")
        }
    }
}
