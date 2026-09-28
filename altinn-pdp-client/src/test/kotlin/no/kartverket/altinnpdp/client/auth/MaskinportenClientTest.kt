package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TOKEN_PATH
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestKeys
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.fixedClock
import no.kartverket.altinnpdp.client.support.maskinportenConfig
import no.kartverket.altinnpdp.client.support.maskinportenTokenResponse
import no.kartverket.altinnpdp.client.support.testHttpClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaskinportenClientTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private fun client() =
        MaskinportenClient(maskinportenConfig(tokenUrl = server.baseUrl + TOKEN_PATH), testHttpClient, clock = fixedClock())

    private fun offlineClient(config: MaskinportenConfig) =
        MaskinportenClient(config, testHttpClient, clock = fixedClock())

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
        server.on(TOKEN_PATH) { TestResponse(body = maskinportenTokenResponse("maskinporten-token")) }

        client().getToken()

        val request = server.lastRequest(TOKEN_PATH)
        assertEquals("POST", request.method)
        assertEquals("application/x-www-form-urlencoded", request.header("Content-Type"))
        assertContains(request.body, "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")

        val form = request.body.split("&").associate {
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
        server.on(TOKEN_PATH) { TestResponse(body = maskinportenTokenResponse("maskinporten-token", expiresIn = 120)) }

        val token = client().getToken()

        assertEquals("maskinporten-token", token.value)
        assertEquals(NOW.plusSeconds(120), token.expiresAt)
    }

    @Test
    fun `fails on an answer it cannot take a token from`() = runBlocking {
        val cases = mapOf(
            "no expires_in" to (maskinportenTokenResponse(expiresIn = null) to "expires_in"),
            "no access_token" to ("""{"token_type":"Bearer"}""" to "access_token"),
            "a body that is not JSON" to ("<html>gateway error</html>" to ""),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case
            server.on(TOKEN_PATH) { TestResponse(body = body) }

            val e = assertFailsWith<MaskinportenException>(why) { client().getToken() }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }
}
