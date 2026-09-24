package no.kartverket.altinnpdp.client

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.testHttpClient
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdpClientTest {

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
        httpClient = testHttpClient,
    )

    private fun decision(value: String) = """{"Response":[{"Decision":"$value"}]}"""

    private suspend fun PdpClient.authorizeSample() =
        authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "test-resource", "923609016", "read")

    @Test
    fun `sends the bearer token and the subscription key the gateway requires`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        client(server.baseUrl).authorizeSample()

        val request = server.lastRequest(path)
        assertEquals("POST", request.method)
        assertEquals("Bearer altinn-token", request.header("Authorization"))
        assertEquals("subscription-key", request.header(PdpClient.SUBSCRIPTION_KEY_HEADER))
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun `sends the XACML request body`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        client(server.baseUrl).authorizeSample()

        val body = server.lastRequest(path).body
        assertContains(body, """"attributeId":"urn:altinn:systemuser:uuid","value":"1725580f-70f4-4ace-a748-4f912497a0d7"""")
        assertContains(body, """"attributeId":"urn:altinn:resource","value":"test-resource"""")
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

            assertEquals(expectedDecision, client(server.baseUrl).authorizeSample().decision, "for $value")
        }
    }

    @Test
    fun `reads the decision from a camelCase response too`() = runBlocking {
        server.on(path) { TestResponse(body = """{"response":[{"decision":"Deny"}]}""") }

        assertEquals(PdpDecision.DENY, client(server.baseUrl).authorizeSample().decision)
    }

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

        assertFailsWith<IllegalArgumentException> { client.authorize(" ", "test-resource", "923609016", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "", "923609016", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "test-resource", "", "read") }
        assertFailsWith<IllegalArgumentException> { client.authorize("1725580f-70f4-4ace-a748-4f912497a0d7", "test-resource", "923609016", " ") }
        Unit
    }

    @Test
    fun `surfaces the obligations and status URN Altinn attaches to a permit`() = runBlocking {
        val body = """
            {"response":[{"decision":"Permit","status":{"statusCode":{"value":"urn:oasis:names:tc:xacml:1.0:status:ok"}},
            "obligations":[{"id":"urn:altinn:obligation:authenticationLevel1","attributeAssignment":[
            {"attributeId":"urn:altinn:obligation1-assignment1","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel"}]},
            {"id":"urn:altinn:obligation:authenticationLevel2","attributeAssignment":[
            {"attributeId":"urn:altinn:obligation2-assignment2","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel-org"}]}]}]}
        """.trimIndent().replace("\n", "")
        server.on(path) { TestResponse(body = body) }

        val authorization = client(server.baseUrl).authorizeSample()

        assertEquals(PdpDecision.PERMIT, authorization.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:ok", authorization.statusCode)
        assertEquals(3, authorization.minimumAuthenticationLevel)
        assertEquals(3, authorization.minimumAuthenticationLevelOrg)
        assertEquals(2, authorization.obligations.size)
    }

    @Test
    fun `keeps the processing-error status that marks an unevaluatable request`() = runBlocking {
        val body = """
            {"response":[{"decision":"Indeterminate",
            "status":{"statusCode":{"value":"urn:oasis:names:tc:xacml:1.0:status:processing-error"}}}]}
        """.trimIndent().replace("\n", "")
        server.on(path) { TestResponse(body = body) }

        val authorization = client(server.baseUrl).authorizeSample()

        assertEquals(PdpDecision.INDETERMINATE, authorization.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:processing-error", authorization.statusCode)
        assertTrue(authorization.obligations.isEmpty())
    }

    @Test
    fun `a decision with no obligations reports no authentication level`() = runBlocking {
        server.on(path) { TestResponse(body = decision("Permit")) }

        val authorization = client(server.baseUrl).authorizeSample()

        assertNull(authorization.minimumAuthenticationLevel)
        assertNull(authorization.statusCode)
    }

    @Test
    fun `refuses a response carrying more decisions than were asked for`() = runBlocking {
        server.on(path) { TestResponse(body = """{"response":[{"decision":"Permit"},{"decision":"Deny"}]}""") }

        val e = assertFailsWith<PdpException> { client(server.baseUrl).authorizeSample() }

        assertContains(e.message!!, "2 Response entries")
    }
}
