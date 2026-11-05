package no.kartverket.altinnpdp.client.internal.maskinporten

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.MaskinportenException
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestKeys
import no.kartverket.altinnpdp.client.support.assertNoTokenIn
import no.kartverket.altinnpdp.client.support.fixedClock
import no.kartverket.altinnpdp.client.support.maskinportenConfig
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.recording
import no.kartverket.altinnpdp.client.support.response
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaskinportenClientTest {

    private val sent = mutableListOf<OutgoingRequest>()

    private fun clientAnswering(body: String) =
        MaskinportenClient(maskinportenConfig(), recording(sent) { response(200, "application/json", body) }, clock = fixedClock())

    private fun offlineClient(config: MaskinportenConfig) =
        MaskinportenClient(config, OutgoingHttpClient { error("unexpected call to ${it.url}") }, clock = fixedClock())

    @Test
    fun `signs the client assertion with the configured key`() {
        val assertion = offlineClient(maskinportenConfig()).createClientAssertion()

        val jwt = SignedJWT.parse(assertion)
        assertTrue(jwt.verify(RSASSAVerifier(TestKeys.rsa.toRSAPublicKey())), "the assertion should verify")
        assertEquals("RS256", jwt.header.algorithm.name)
        assertEquals("test-key", jwt.header.keyID, "Maskinporten needs the kid to pick the right public key")
    }

    @Test
    fun `puts the claims Maskinporten's JWT grant requires on the assertion`() {
        val config = maskinportenConfig()

        val claims = SignedJWT.parse(offlineClient(config).createClientAssertion()).jwtClaimsSet

        assertEquals("my-client-id", claims.issuer)
        assertEquals(listOf("https://test.maskinporten.no/"), claims.audience)
        assertEquals(AltinnScopes.AUTHORIZE, claims.getStringClaim("scope"))
        assertNotNull(claims.jwtid, "a jti is required so Maskinporten can reject replays")
        assertEquals(NOW.epochSecond, claims.issueTime.toInstant().epochSecond)
        assertEquals(NOW.plusSeconds(60).epochSecond, claims.expirationTime.toInstant().epochSecond)
    }

    @Test
    fun `posts the JWT grant as a form-encoded body`() = runBlocking {
        clientAnswering(maskinportenTokenResponse("maskinporten-token")).getToken()

        val request = sent.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("https://test.maskinporten.no/token", request.url.toString())
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val body = request.body!!.decodeToString()
        assertContains(body, "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")

        val form = body.split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            URLDecoder.decode(key, UTF_8) to URLDecoder.decode(value, UTF_8)
        }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        assertTrue(
            SignedJWT.parse(form["assertion"]).verify(RSASSAVerifier(TestKeys.rsa.toRSAPublicKey())),
            "the assertion in the form body should be the signed client assertion",
        )
    }

    @Test
    fun `reads the access token and its lifetime from the response`() = runBlocking {
        val token = clientAnswering(maskinportenTokenResponse("maskinporten-token", expiresIn = 120)).getToken()

        assertEquals("maskinporten-token", token.value)
        assertEquals(NOW.plusSeconds(120), token.expiresAt)
    }

    @Test
    fun `fails on an answer it cannot take a token from`() = runBlocking {
        val cases = mapOf(
            "no expires_in" to (maskinportenTokenResponse(expiresIn = null) to "expires_in"),
            "no access_token" to ("""{"token_type":"Bearer"}""" to "access_token"),
            "an access_token with a newline" to ("""{"access_token":"mp\ntoken","expires_in":3600}""" to "bearer token"),
            "a body that is not JSON" to ("<html>gateway error</html>" to ""),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case

            val e = assertFailsWith<MaskinportenException>(why) { clientAnswering(body).getToken() }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }

    @Test
    fun `keeps the token out of the error when the rest of the answer is unusable`() = runBlocking {
        val e = assertFailsWith<MaskinportenException> {
            clientAnswering("""{"access_token":"secret-mp-token","expires_in":"soon"}""").getToken()
        }

        assertNoTokenIn(e, "secret-mp-token")
    }

    @Test
    fun `keeps the token out of the error when it is not a valid bearer token`() = runBlocking {
        val e = assertFailsWith<MaskinportenException> {
            clientAnswering("""{"access_token":"secret-mp\ntoken","expires_in":3600}""").getToken()
        }

        assertNoTokenIn(e, "secret-mp")
    }
}
