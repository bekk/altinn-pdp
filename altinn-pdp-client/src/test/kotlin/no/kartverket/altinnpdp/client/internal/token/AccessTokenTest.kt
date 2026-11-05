package no.kartverket.altinnpdp.client.internal.token

import no.kartverket.altinnpdp.client.support.NOW
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensWithAnExpiry")
    fun `masks the token value of the internal tokens with an expiry`(kind: String, token: Any) {
        val rendered = token.toString()
        assertFalse(rendered.contains("super-secret-jwt"), "the token value must never be printed")
        assertTrue(rendered.contains("***"))
        assertTrue(rendered.contains(NOW.toString()), "the expiry is safe to print and useful in logs")
    }

    companion object {
        @JvmStatic
        fun tokensWithAnExpiry(): List<Arguments> = listOf(
            Arguments.of("AltinnToken", AltinnToken("super-secret-jwt", NOW)),
            Arguments.of("MaskinportenToken", MaskinportenToken("super-secret-jwt", NOW)),
        )
    }
}
