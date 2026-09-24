package no.kartverket.altinnpdp.client

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.support.FakeTokenProvider
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.support.TestHttpServer
import no.kartverket.altinnpdp.client.support.TestResponse
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.testPdpClient
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

    private fun serverAnswers(decision: String = "Permit") {
        server.on(path) { TestResponse(body = pdpDecisionResponse(decision)) }
    }

    @Test
    fun `sends the bearer token and the subscription key the gateway requires`() = runBlocking {
        serverAnswers()

        testPdpClient(server.baseUrl).authorizeSample()

        val request = server.lastRequest(path)
        assertEquals("POST", request.method)
        assertEquals("Bearer altinn-token", request.header("Authorization"))
        assertEquals("subscription-key", request.header(PdpClient.SUBSCRIPTION_KEY_HEADER))
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun `sends the XACML request body`() = runBlocking {
        serverAnswers()

        testPdpClient(server.baseUrl).authorizeSample()

        val body = server.lastRequest(path).body
        assertContains(body, """"attributeId":"urn:altinn:systemuser:uuid","value":"$SAMPLE_SYSTEMUSER_ID"""")
        assertContains(body, """"attributeId":"urn:altinn:resource","value":"test-resource"""")
        assertContains(body, """"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"""")
    }

    @Test
    fun `appends the authorize path to a base URL that ends in a slash`() = runBlocking {
        serverAnswers()

        testPdpClient(server.baseUrl + "/").authorizeSample()

        assertEquals(1, server.requestCount(path))
    }

    @Test
    fun `asks the token provider on every call, leaving caching to the provider`() = runBlocking {
        serverAnswers()
        val provider = FakeTokenProvider()
        val client = testPdpClient(server.baseUrl, tokenProvider = provider)

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
            serverAnswers(value)

            assertEquals(expectedDecision, testPdpClient(server.baseUrl).authorizeSample().decision, "for $value")
        }
    }

    @Test
    fun `reads the decision from a camelCase response too`() = runBlocking {
        server.on(path) { TestResponse(body = """{"response":[{"decision":"Deny"}]}""") }

        assertEquals(PdpDecision.DENY, testPdpClient(server.baseUrl).authorizeSample().decision)
    }

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        server.on(path) { TestResponse(status = 403, body = "forbidden") }

        val e = assertFailsWith<PdpException> { testPdpClient(server.baseUrl).authorizeSample() }

        assertEquals(403, e.statusCode)
        assertEquals("forbidden", e.responseBody)
    }

    @Test
    fun `fails loudly on an answer it cannot use, rather than treating it as a deny`() = runBlocking {
        val cases = mapOf(
            "a body that is not JSON" to ("<html>gateway error</html>" to "parse"),
            "no decision at all" to ("""{"Response":[]}""" to "no Response entries"),
            "a decision it does not recognise" to (pdpDecisionResponse("Maybe") to "Maybe"),
            "more decisions than were asked for" to
                ("""{"response":[{"decision":"Permit"},{"decision":"Deny"}]}""" to "2 Response entries"),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case
            server.on(path) { TestResponse(body = body) }

            val e = assertFailsWith<PdpException>(why) { testPdpClient(server.baseUrl).authorizeSample() }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        assertContains(
            assertFailsWith<PdpException> { testPdpClient("http://127.0.0.1:1").authorizeSample() }.message!!,
            "Altinn PDP",
        )
    }

    @Test
    fun `rejects blank arguments before making a call`() = runBlocking {
        val client = testPdpClient("http://127.0.0.1:1")

        assertFailsWith<IllegalArgumentException> {
            client.authorize(SystemUserId(" "), ResourceId("test-resource"), OrganizationNumber("923609016"), ActionId("read"))
        }
        assertFailsWith<IllegalArgumentException> {
            client.authorize(SystemUserId(SAMPLE_SYSTEMUSER_ID), ResourceId(""), OrganizationNumber("923609016"), ActionId("read"))
        }
        assertFailsWith<IllegalArgumentException> {
            client.authorize(SystemUserId(SAMPLE_SYSTEMUSER_ID), ResourceId("test-resource"), OrganizationNumber(""), ActionId("read"))
        }
        assertFailsWith<IllegalArgumentException> {
            client.authorize(SystemUserId(SAMPLE_SYSTEMUSER_ID), ResourceId("test-resource"), OrganizationNumber("923609016"), ActionId(" "))
        }
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

        val authorization = testPdpClient(server.baseUrl).authorizeSample()

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

        val authorization = testPdpClient(server.baseUrl).authorizeSample()

        assertEquals(PdpDecision.INDETERMINATE, authorization.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:processing-error", authorization.statusCode)
        assertTrue(authorization.obligations.isEmpty())
    }

    @Test
    fun `a decision with no obligations reports no authentication level`() = runBlocking {
        serverAnswers()

        val authorization = testPdpClient(server.baseUrl).authorizeSample()

        assertNull(authorization.minimumAuthenticationLevel)
        assertNull(authorization.statusCode)
    }
}
