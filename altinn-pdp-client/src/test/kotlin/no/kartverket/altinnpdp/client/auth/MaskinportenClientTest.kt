package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.support.MutableClock
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
import java.time.Clock
import java.time.Duration
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

    /** A client pointed at the test server, on a clock that does not move unless a test moves it. */
    private fun client(clock: Clock = fixedClock(), refreshLeeway: Duration = Duration.ofSeconds(30)) =
        MaskinportenClient(
            maskinportenConfig(tokenUrl = server.baseUrl + TOKEN_PATH),
            testHttpClient,
            clock = clock,
            refreshLeeway = refreshLeeway,
        )

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
        val config = maskinportenConfig(scopes = listOf("altinn:a", "altinn:b"))

        val claims = SignedJWT.parse(offlineClient(config).createClientAssertion()).jwtClaimsSet

        assertEquals("my-client-id", claims.issuer)
        assertEquals(listOf("https://test.maskinporten.no/"), claims.audience)
        assertEquals("altinn:a altinn:b", claims.getStringClaim("scope"))
        assertNotNull(claims.jwtid, "a jti is required so Maskinporten can reject replays")
        assertEquals(NOW.epochSecond, claims.issueTime.toInstant().epochSecond)
        assertEquals(NOW.plusSeconds(60).epochSecond, claims.expirationTime.toInstant().epochSecond)
    }

    @Test
    fun `gives each assertion its own jti`() {
        val client = offlineClient(maskinportenConfig())

        val first = SignedJWT.parse(client.createClientAssertion()).jwtClaimsSet.jwtid
        val second = SignedJWT.parse(client.createClientAssertion()).jwtClaimsSet.jwtid

        assertTrue(first != second, "a reused jti would be rejected as a replay")
    }

    @Test
    fun `refuses a JWK it cannot sign with, at construction rather than on the first call`() {
        val cases = mapOf(
            "a key that is not RSA" to (ECKeyGenerator(Curve.P_256).keyID("ec-key").generate().toJSONString() to "RSA"),
            "a key with no private material" to (TestKeys.rsa.toPublicJWK().toJSONString() to "private key"),
            // Nothing specific to say about a string that is not a JWK at all, beyond refusing it.
            "a string that is not a JWK" to ("not-a-jwk" to ""),
        )
        for ((why, case) in cases) {
            val (jwk, expectedInMessage) = case

            val e = assertFailsWith<MaskinportenException>(why) {
                offlineClient(maskinportenConfig(jwk = jwk))
            }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
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
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        server.on(TOKEN_PATH) { TestResponse(status = 400, body = """{"error":"invalid_grant"}""") }

        val e = assertFailsWith<MaskinportenException> { client().getToken() }

        assertEquals(400, e.statusCode)
        assertEquals("""{"error":"invalid_grant"}""", e.responseBody)
        assertContains(e.message!!, "invalid_grant")
    }

    @Test
    fun `fails on an answer it cannot take a token from`() = runBlocking {
        val cases = mapOf(
            "no expires_in" to (maskinportenTokenResponse(expiresIn = null) to "expires_in"),
            "no access_token" to ("""{"token_type":"Bearer"}""" to "access_token"),
            // Nothing specific to say about a body that is not JSON, beyond refusing it.
            "a body that is not JSON" to ("<html>gateway error</html>" to ""),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case
            server.on(TOKEN_PATH) { TestResponse(body = body) }

            val e = assertFailsWith<MaskinportenException>(why) { client().getToken() }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        val client = MaskinportenClient(
            maskinportenConfig(tokenUrl = "http://127.0.0.1:1/token"),
            testHttpClient,
            clock = fixedClock(),
        )

        assertContains(assertFailsWith<MaskinportenException> { client.getToken() }.message!!, "Maskinporten")
    }

    @Test
    fun `serves a cached token instead of asking Maskinporten again`() = runBlocking {
        server.on(TOKEN_PATH) { TestResponse(body = maskinportenTokenResponse("maskinporten-token")) }
        val client = client()

        repeat(3) { client.getToken() }

        assertEquals(1, server.requestCount(TOKEN_PATH))
    }

    @Test
    fun `fetches a new token once the cached one nears expiry`() = runBlocking {
        var issued = 0
        server.on(TOKEN_PATH) { TestResponse(body = maskinportenTokenResponse("token-${++issued}", expiresIn = 120)) }
        val clock = MutableClock()
        val client = client(clock = clock, refreshLeeway = Duration.ofSeconds(30))

        assertEquals("token-1", client.getToken().value)
        clock.advance(Duration.ofSeconds(89))
        assertEquals("token-1", client.getToken().value, "still outside the refresh window")
        clock.advance(Duration.ofSeconds(1))
        assertEquals("token-2", client.getToken().value, "now within the 30s refresh leeway")
        assertEquals(2, server.requestCount(TOKEN_PATH))
    }
}
