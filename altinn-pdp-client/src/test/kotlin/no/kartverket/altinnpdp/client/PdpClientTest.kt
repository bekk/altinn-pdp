package no.kartverket.altinnpdp.client

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse

class PdpClientTest {

    /** One server per test, started and stopped around it rather than inside every test body. */
    private lateinit var server: TestHttpServer

    @BeforeTest
    fun startServer() {
        server = TestHttpServer.start()
    }

    @AfterTest
    fun stopServer() = server.close()

    private val path = PdpClient.AUTHORIZE_PATH

    private class FakeTokenProvider(private val token: String = "altinn-token") : AltinnTokenProvider {
        var calls = 0
            private set

        override suspend fun getAltinnToken(): AccessToken {
            calls++
            return AccessToken(token, Instant.MAX)
        }
    }

    private fun client(
        baseUrl: String,
        tokenProvider: AltinnTokenProvider = FakeTokenProvider(),
        subscriptionKey: String = "subscription-key",
    ) = PdpClient(
        platformBaseUrl = baseUrl,
        tokenProvider = tokenProvider,
        subscriptionKey = subscriptionKey,
    )

    private fun decision(value: String) = """{"Response":[{"Decision":"$value"}]}"""

    private suspend fun PdpClient.authorizeSample() =
        authorize("sys-1", "urn:altinn:resource:x", "923609016", "read")

    // --- the request ---

    @Test
    fun `sends the bearer token and the subscription key the gateway requires`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        client(server.baseUrl).authorizeSample()

        val request = server.lastRequest(path)
        assertEquals("POST", request.method)
        assertEquals("Bearer altinn-token", request.header("Authorization"))
        // Without this header API Management rejects the call with a 401 before the PDP ever
        // sees it, which reads like an authentication bug rather than a missing key.
        assertEquals("subscription-key", request.header(PdpClient.SUBSCRIPTION_KEY_HEADER))
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun `sends the XACML request body`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        client(server.baseUrl).authorizeSample()

        val body = server.lastRequest(path).body
        assertContains(body, """"attributeId":"urn:altinn:systemuser:uuid","value":"sys-1"""")
        assertContains(body, """"attributeId":"urn:altinn:resource","value":"urn:altinn:resource:x"""")
        assertContains(body, """"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"""")
    }

    @Test
    fun `appends the authorize path to a base URL that ends in a slash`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        client(server.baseUrl + "/").authorizeSample()

        assertEquals(1, server.requestCount(path))
    }

    @Test
    fun `asks the token provider on every call, leaving caching to the provider`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }
        val provider = FakeTokenProvider()
        val client = client(server.baseUrl, tokenProvider = provider)

        client.authorizeSample()
        client.authorizeSample()

        assertEquals(2, provider.calls)
    }

    // --- the decision ---

    @Test
    fun `maps every XACML decision Altinn can answer with`() = runBlocking {
        val expected = mapOf(
            "Permit" to PdpDecision.PERMIT,
            "Deny" to PdpDecision.DENY,
            "NotApplicable" to PdpDecision.NOT_APPLICABLE,
            "Indeterminate" to PdpDecision.INDETERMINATE,
        )
        for ((value, expectedDecision) in expected) {
            server.on(path) { TestResponse(body = decision(value)) }

            assertEquals(expectedDecision, client(server.baseUrl).authorizeSample(), "for $value")
        }
    }

    @Test
    fun `isPermitted collapses the decision to a boolean`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }
        assertTrue(client(server.baseUrl).isPermitted("sys-1", "urn:res", "923609016", "read"))
        server.on(path) { TestResponse(body = decision("NotApplicable")) }
        assertFalse(client(server.baseUrl).isPermitted("sys-1", "urn:res", "923609016", "read"))
    }

    @Test
    fun `reads the decision from a camelCase response too`() = runBlocking {
        server.on(path) { TestResponse(body = """{"response":[{"decision":"Deny"}]}""") }

        assertEquals(PdpDecision.DENY, client(server.baseUrl).authorizeSample())
    }

    // --- failures ---

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        server.on(path) { TestResponse(status = 403, body = "forbidden") }

        val e = assertFailsWith<PdpException> { client(server.baseUrl).authorizeSample() }

        assertEquals(403, e.statusCode)
        assertEquals("forbidden", e.responseBody)
    }

    @Test
    fun `fails on a response that is not JSON`() = runBlocking {
        server.on(path) { TestResponse(body = "<html>gateway error</html>") }

        assertContains(assertFailsWith<PdpException> { client(server.baseUrl).authorizeSample() }.message!!, "parse")
    }

    @Test
    fun `fails when the response carries no decision`() = runBlocking {
        server.on(path) { TestResponse(body = """{"Response":[]}""") }

        val e = assertFailsWith<PdpException> { client(server.baseUrl).authorizeSample() }
        assertContains(e.message!!, "no Response entries")
    }

    @Test
    fun `fails loudly on a decision it does not recognise rather than treating it as a deny`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Maybe")) }

        val e = assertFailsWith<PdpException> { client(server.baseUrl).authorizeSample() }

        assertContains(e.message!!, "Maybe")
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        assertContains(
            assertFailsWith<PdpException> { client("http://127.0.0.1:1").authorizeSample() }.message!!,
            "Altinn PDP",
        )
    }

    @Test
    fun `rejects blank arguments before making a call`() = runBlocking {
        val client = client("http://127.0.0.1:1")

        assertFailsWith<IllegalArgumentException> { client.authorize(" ", "urn:res", "923609016", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("sys-1", "", "923609016", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("sys-1", "urn:res", "", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("sys-1", "urn:res", "923609016", " ") }
        Unit
    }
}
