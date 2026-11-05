package no.kartverket.altinnpdp.client

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.PLATFORM_URL
import no.kartverket.altinnpdp.client.support.SAMPLE_PID
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.support.authorizeSample
import no.kartverket.altinnpdp.client.support.authorizeSamplePerson
import no.kartverket.altinnpdp.client.support.pdpDecisionResponse
import no.kartverket.altinnpdp.client.support.recording
import no.kartverket.altinnpdp.client.support.response
import no.kartverket.altinnpdp.client.support.testPdpClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdpClientTest {

    private val sent = mutableListOf<OutgoingRequest>()

    private fun pdpAnswering(status: Int = 200, body: String = pdpDecisionResponse()) =
        testPdpClient(recording(sent) { response(status, "application/json", body) })

    private val sentBody get() = sent.single().body!!.decodeToString()

    @Test
    fun `sends the bearer token and the subscription key the gateway requires`() = runBlocking {
        pdpAnswering().authorizeSample()

        val request = sent.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("$PLATFORM_URL${PdpClient.AUTHORIZE_PATH}", request.url.toString())
        assertEquals("Bearer altinn-token", request.headers["Authorization"])
        assertEquals("subscription-key", request.headers[PdpClient.SUBSCRIPTION_KEY_HEADER])
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `sends the exact XACML body Altinn's PDP expects`() = runBlocking {
        pdpAnswering().authorizeSample()

        val expected = """
            {"request":{"returnPolicyIdList":true,"accessSubject":[{"attribute":[{"attributeId":"urn:altinn:systemuser:uuid","value":"$SAMPLE_SYSTEMUSER_ID"}]}],"action":[{"attribute":[{"attributeId":"urn:oasis:names:tc:xacml:1.0:action:action-id","value":"read"}]}],"resource":[{"attribute":[{"attributeId":"urn:altinn:resource","value":"test-resource"},{"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"}]}]}}
        """.trimIndent()
        assertEquals(expected, sentBody)
    }

    @Test
    fun `sends a pid as the person asking, with the rest of the body unchanged`() = runBlocking {
        pdpAnswering().authorizeSamplePerson()

        val expected = """
            {"request":{"returnPolicyIdList":true,"accessSubject":[{"attribute":[{"attributeId":"urn:altinn:person:identifier-no","value":"$SAMPLE_PID"}]}],"action":[{"attribute":[{"attributeId":"urn:oasis:names:tc:xacml:1.0:action:action-id","value":"read"}]}],"resource":[{"attribute":[{"attributeId":"urn:altinn:resource","value":"test-resource"},{"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"}]}]}}
        """.trimIndent()
        assertEquals(expected, sentBody)
    }

    @Test
    fun `sends resource attributes in the resource category, after the customer`() = runBlocking {
        pdpAnswering().authorize(
            SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
            ResourceId.parse("test-resource"),
            OrganizationNumber.parse("923609016"),
            ActionId.parse("download"),
            listOf(
                ResourceAttribute.parse("urn:altinn:subresource", "metadata"),
                ResourceAttribute.parse("urn:kv:geonorge", "service"),
            ),
        )

        val expected = """
            {"request":{"returnPolicyIdList":true,"accessSubject":[{"attribute":[{"attributeId":"urn:altinn:systemuser:uuid","value":"$SAMPLE_SYSTEMUSER_ID"}]}],"action":[{"attribute":[{"attributeId":"urn:oasis:names:tc:xacml:1.0:action:action-id","value":"download"}]}],"resource":[{"attribute":[{"attributeId":"urn:altinn:resource","value":"test-resource"},{"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"},{"attributeId":"urn:altinn:subresource","value":"metadata"},{"attributeId":"urn:kv:geonorge","value":"service"}]}]}}
        """.trimIndent()
        assertEquals(expected, sentBody)
    }

    @Test
    fun `refuses two resource attributes of one type without asking the PDP`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            pdpAnswering().authorize(
                SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
                ResourceId.parse("test-resource"),
                OrganizationNumber.parse("923609016"),
                ActionId.parse("download"),
                listOf(ResourceAttribute.parse("urn:kv:geonorge", "dataset"), ResourceAttribute.parse("urn:kv:geonorge", "service")),
            )
        }
        assertTrue(sent.isEmpty())
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
            assertEquals(expectedDecision, pdpAnswering(body = pdpDecisionResponse(value)).authorizeSample().decision, "for $value")
        }
    }

    @Test
    fun `reads the PascalCase spelling from Altinn's documentation too`() = runBlocking {
        assertEquals(PdpDecision.DENY, pdpAnswering(body = """{"Response":[{"Decision":"Deny"}]}""").authorizeSample().decision)
    }

    @Test
    fun `surfaces a non-200 with the status and body on the exception`() = runBlocking {
        val e = assertFailsWith<PdpException> { pdpAnswering(status = 403, body = "forbidden").authorizeSample() }

        assertEquals(403, e.statusCode)
        assertEquals("forbidden", e.responseBody)
    }

    @Test
    fun `fails loudly on an answer it cannot use, rather than treating it as a deny`() = runBlocking {
        val cases = mapOf(
            "a body that is not JSON" to ("<html>gateway error</html>" to "parse"),
            "no decision at all" to ("""{"response":[]}""" to "no Response entries"),
            "a decision it does not recognise" to (pdpDecisionResponse("Maybe") to "Maybe"),
            "more decisions than were asked for" to
                ("""{"response":[{"decision":"Permit"},{"decision":"Deny"}]}""" to "2 Response entries"),
        )
        for ((why, case) in cases) {
            val (body, expectedInMessage) = case

            val e = assertFailsWith<PdpException>(why) { pdpAnswering(body = body).authorizeSample() }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }

    @Test
    fun `wraps a connection failure rather than leaking an IOException`() = runBlocking {
        val unreachable = testPdpClient(OutgoingHttpClient { throw IOException("Connection refused") })

        assertContains(assertFailsWith<PdpException> { unreachable.authorizeSample() }.message!!, "Altinn PDP")
    }

    @Test
    fun `surfaces the obligations and status URN of a real Permit from TT02`() = runBlocking {
        val body = """
            {"response":[{"decision":"Permit","status":{"statusMessage":null,"statusDetails":null,
            "statusCode":{"value":"urn:oasis:names:tc:xacml:1.0:status:ok","statusCode":null}},
            "obligations":[{"id":"urn:altinn:obligation:authenticationLevel1","attributeAssignment":[
            {"attributeId":"urn:altinn:obligation1-assignment1","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel",
            "dataType":"http://www.w3.org/2001/XMLSchema#integer","issuer":null}]},
            {"id":"urn:altinn:obligation:authenticationLevel2","attributeAssignment":[
            {"attributeId":"urn:altinn:obligation2-assignment2","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel-org",
            "dataType":"http://www.w3.org/2001/XMLSchema#integer","issuer":null}]}],
            "associateAdvice":null,"category":null,"policyIdentifierList":null}]}
        """.trimIndent().replace("\n", "")

        val authorization = pdpAnswering(body = body).authorizeSample()

        assertEquals(PdpDecision.PERMIT, authorization.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:ok", authorization.statusCode)
        assertEquals(3, authorization.minimumAuthenticationLevel)
        assertEquals(3, authorization.minimumAuthenticationLevelOrg)
        assertEquals(2, authorization.obligations.size)
    }

    @Test
    fun `keeps the processing-error status of a real Indeterminate from TT02`() = runBlocking {
        val body = """
            {"response":[{"decision":"Indeterminate","status":{"statusMessage":null,"statusDetails":null,
            "statusCode":{"value":"urn:oasis:names:tc:xacml:1.0:status:processing-error","statusCode":null}},
            "obligations":null,"associateAdvice":null,"category":null,"policyIdentifierList":null}]}
        """.trimIndent().replace("\n", "")

        val authorization = pdpAnswering(body = body).authorizeSample()

        assertEquals(PdpDecision.INDETERMINATE, authorization.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:processing-error", authorization.statusCode)
        assertTrue(authorization.obligations.isEmpty())
    }
}
