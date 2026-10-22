package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.maskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.support.serveBothTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessTokenTest {

    @Test
    fun `keeps the token`() {
        assertEquals("secret-token", AccessToken.of("secret-token").value)
    }

    @Test
    fun `does not show the token when printed`() {
        val token = AccessToken.of("secret-token")

        assertFalse("secret-token" in "Calling with $token")
        assertFalse("secret-token" in listOf(token).toString(), "nor inside a collection")
    }

    @Test
    fun `does not show the token in an exception message`() {
        val token = AccessToken.of("secret-token")

        val e = IllegalStateException("Altinn refused $token")

        assertFalse("secret-token" in e.stackTraceToString())
    }

    @Test
    fun `does not show the token the Maskinporten provider hands out`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.serveBothTokens()

            val token = maskinportenAltinnTokenProvider(server).accessToken()

            assertFalse(token.value in token.toString())
        }
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
