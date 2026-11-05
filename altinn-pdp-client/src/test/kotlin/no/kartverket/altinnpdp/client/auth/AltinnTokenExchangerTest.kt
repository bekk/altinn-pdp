package no.kartverket.altinnpdp.client.auth

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.PLATFORM_URL
import no.kartverket.altinnpdp.client.support.assertNoTokenIn
import no.kartverket.altinnpdp.client.support.recording
import no.kartverket.altinnpdp.client.support.response
import no.kartverket.altinnpdp.client.support.signedJwt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AltinnTokenExchangerTest {

    private val sent = mutableListOf<OutgoingRequest>()

    private fun exchangerAnswering(body: String) =
        AltinnTokenExchanger(PLATFORM_URL, recording(sent) { response(200, "text/plain", body) })

    private val maskinportenToken = MaskinportenToken("maskinporten-token", NOW.plusSeconds(300))

    @Test
    fun `sends the Maskinporten token as a bearer token on a GET`() = runBlocking {
        exchangerAnswering(signedJwt(NOW.plusSeconds(300))).exchange(maskinportenToken)

        val request = sent.single()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("$PLATFORM_URL${AltinnTokenExchanger.EXCHANGE_PATH}", request.url.toString())
        assertEquals("Bearer maskinporten-token", request.headers["Authorization"])
    }

    @Test
    fun `takes the expiry from the returned token's own exp claim`() = runBlocking {
        val expiresAt = NOW.plusSeconds(300)

        val token = exchangerAnswering(signedJwt(expiresAt)).exchange(maskinportenToken)

        assertEquals(expiresAt.epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `trims whitespace around the returned token`() = runBlocking {
        val jwt = signedJwt(NOW.plusSeconds(300))

        assertEquals(jwt, exchangerAnswering("  $jwt\n").exchange(maskinportenToken).value)
    }

    @Test
    fun `fails on an answer that is not a token it can use`() = runBlocking {
        val cases = mapOf(
            "no exp claim" to (signedJwt(expiresAt = null) to "exp"),
            "an empty body" to ("" to "empty"),
            "a body that is not a bearer token" to ("<html>gateway error</html>" to "bearer token"),
            "a body that is not a JWT" to ("not-a-jwt" to "JWT"),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case

            val e = assertFailsWith<AltinnException>(why) { exchangerAnswering(body).exchange(maskinportenToken) }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }

    @Test
    fun `keeps the token out of the error when it has no usable expiry`() = runBlocking {
        val jwt = signedJwt(expiresAt = null)

        val e = assertFailsWith<AltinnException> { exchangerAnswering(jwt).exchange(maskinportenToken) }

        assertNoTokenIn(e, jwt)
    }

    @Test
    fun `keeps the token out of the error when it is not a JWT`() = runBlocking {
        val e = assertFailsWith<AltinnException> { exchangerAnswering("secret.altinn.token").exchange(maskinportenToken) }

        assertNoTokenIn(e, "secret.altinn.token")
    }

    @Test
    fun `fails without the token in the error when the token is not a valid bearer token`() = runBlocking {
        val jwt = signedJwt(NOW.plusSeconds(300))
        val split = jwt.indexOf('.') + 5

        val e = assertFailsWith<AltinnException> {
            exchangerAnswering(jwt.substring(0, split) + "\n" + jwt.substring(split)).exchange(maskinportenToken)
        }

        assertContains(e.message!!, "bearer token")
        assertNoTokenIn(e, jwt.substring(split))
    }
}
