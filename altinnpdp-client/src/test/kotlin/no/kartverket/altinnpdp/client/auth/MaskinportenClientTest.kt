package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.support.MutableClock
import no.kartverket.altinnpdp.client.support.NOW
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestKeys
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.fixedClock

class MaskinportenClientTest {

    private val tokenPath = "/token"

    private fun config(
        tokenUrl: String,
        scopes: List<String> = listOf(AltinnScopes.AUTHORIZE),
        jwk: String = TestKeys.rsa.toJSONString(),
        assertionLifetime: Duration = Duration.ofSeconds(60),
    ) = MaskinportenConfig(
        tokenUrl = tokenUrl,
        clientId = "my-client-id",
        jwk = jwk,
        scopes = scopes,
        assertionLifetime = assertionLifetime,
    )

    private fun tokenResponse(accessToken: String = "maskinporten-token", expiresIn: Long? = 3600) =
        if (expiresIn == null) {
            """{"access_token":"$accessToken","token_type":"Bearer"}"""
        } else {
            """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn}"""
        }

    // --- the client assertion: if this is wrong every call to Maskinporten fails with a 400 ---

    @Test
    fun `signs the client assertion with the configured key`() {
        val assertion = MaskinportenClient(config("https://test.maskinporten.no/token"), clock = fixedClock())
            .createClientAssertion()

        val jwt = SignedJWT.parse(assertion)
        assertTrue(jwt.verify(RSASSAVerifier(TestKeys.rsa.toRSAPublicKey())), "the assertion should verify")
        assertEquals("RS256", jwt.header.algorithm.name)
        assertEquals("test-key", jwt.header.keyID, "Maskinporten needs the kid to pick the right public key")
    }

    @Test
    fun `puts the claims Maskinporten's JWT grant requires on the assertion`() {
        val config = config("https://test.maskinporten.no/token", scopes = listOf("altinn:a", "altinn:b"))

        val claims = SignedJWT.parse(MaskinportenClient(config, clock = fixedClock()).createClientAssertion()).jwtClaimsSet

        assertEquals("my-client-id", claims.issuer)
        assertEquals(listOf("https://test.maskinporten.no/"), claims.audience)
        // Space-separated, not a JSON array - this is the OAuth scope encoding, not a list.
        assertEquals("altinn:a altinn:b", claims.getStringClaim("scope"))
        assertNotNull(claims.jwtid, "a jti is required so Maskinporten can reject replays")
        assertEquals(NOW.epochSecond, claims.issueTime.toInstant().epochSecond)
        assertEquals(NOW.plusSeconds(60).epochSecond, claims.expirationTime.toInstant().epochSecond)
    }

    @Test
    fun `gives each assertion its own jti`() {
        val client = MaskinportenClient(config("https://test.maskinporten.no/token"), clock = fixedClock())

        val first = SignedJWT.parse(client.createClientAssertion()).jwtClaimsSet.jwtid
        val second = SignedJWT.parse(client.createClientAssertion()).jwtClaimsSet.jwtid

        assertTrue(first != second, "a reused jti would be rejected as a replay")
    }

    // --- the key itself ---

    @Test
    fun `rejects a JWK that is not RSA`() {
        val ec = ECKeyGenerator(Curve.P_256).keyID("ec-key").generate()

        val e = assertFailsWith<MaskinportenException> {
            MaskinportenClient(config("https://test.maskinporten.no/token", jwk = ec.toJSONString()))
        }
        assertContains(e.message!!, "RSA")
    }

    @Test
    fun `rejects a JWK with no private key material`() {
        val publicOnly = TestKeys.rsa.toPublicJWK().toJSONString()

        val e = assertFailsWith<MaskinportenException> {
            MaskinportenClient(config("https://test.maskinporten.no/token", jwk = publicOnly))
        }
        assertContains(e.message!!, "private key")
    }

    @Test
    fun `rejects a JWK that is not parseable`() {
        assertFailsWith<MaskinportenException> {
            MaskinportenClient(config("https://test.maskinporten.no/token", jwk = "not-a-jwk"))
        }
    }

    // --- the token request ---

    @Test
    fun `posts the JWT grant as a form-encoded body`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = tokenResponse()) }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            client.getToken()

            val request = server.lastRequest(tokenPath)
            assertEquals("POST", request.method)
            assertEquals("application/x-www-form-urlencoded", request.header("Content-Type"))
            // The grant type is a URN, so its colons have to be percent-encoded on the wire.
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
    }

    @Test
    fun `reads the access token and its lifetime from the response`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = tokenResponse(expiresIn = 120)) }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            val token = client.getToken()

            assertEquals("maskinporten-token", token.value)
            assertEquals(NOW.plusSeconds(120), token.expiresAt)
        }
    }

    @Test
    fun `falls back to a short lifetime when the response omits expires_in`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = tokenResponse(expiresIn = null)) }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            assertEquals(NOW.plusSeconds(60), client.getToken().expiresAt)
        }
    }

    // --- failures ---

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(status = 400, body = """{"error":"invalid_grant"}""") }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            val e = assertFailsWith<MaskinportenException> { client.getToken() }

            // Callers are told to branch on statusCode rather than parse the message.
            assertEquals(400, e.statusCode)
            assertEquals("""{"error":"invalid_grant"}""", e.responseBody)
            assertContains(e.message!!, "invalid_grant")
        }
    }

    @Test
    fun `fails when the response carries no access token`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = """{"token_type":"Bearer"}""") }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            assertContains(assertFailsWith<MaskinportenException> { client.getToken() }.message!!, "access_token")
        }
    }

    @Test
    fun `fails when the response is not JSON`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = "<html>gateway error</html>") }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            assertFailsWith<MaskinportenException> { client.getToken() }
            Unit
        }
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        // Nothing is listening on this port.
        val client = MaskinportenClient(config("http://127.0.0.1:1/token"), clock = fixedClock())

        assertContains(assertFailsWith<MaskinportenException> { client.getToken() }.message!!, "Maskinporten")
    }

    // --- caching ---

    @Test
    fun `serves a cached token instead of asking Maskinporten again`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = tokenResponse()) }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            repeat(3) { client.getToken() }

            assertEquals(1, server.requestCount(tokenPath))
        }
    }

    @Test
    fun `fetches a new token once the cached one nears expiry`() = runBlocking {
        TestHttpServer.start().use { server ->
            var issued = 0
            server.on(tokenPath) { TestResponse(body = tokenResponse(accessToken = "token-${++issued}", expiresIn = 120)) }
            val clock = MutableClock()
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = clock, refreshLeeway = Duration.ofSeconds(30))

            assertEquals("token-1", client.getToken().value)
            clock.advance(Duration.ofSeconds(89))
            assertEquals("token-1", client.getToken().value, "still outside the refresh window")
            clock.advance(Duration.ofSeconds(1))
            assertEquals("token-2", client.getToken().value, "now within the 30s refresh leeway")
            assertEquals(2, server.requestCount(tokenPath))
        }
    }

    @Test
    fun `invalidate forces the next call to fetch again`() = runBlocking {
        TestHttpServer.start().use { server ->
            server.on(tokenPath) { TestResponse(body = tokenResponse()) }
            val client = MaskinportenClient(config(server.baseUrl + tokenPath), clock = fixedClock())

            client.getToken()
            client.invalidate()
            client.getToken()

            assertEquals(2, server.requestCount(tokenPath))
        }
    }
}
