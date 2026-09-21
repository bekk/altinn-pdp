package no.kartverket.altinnpdp.client.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class XacmlAuthorizationResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String) = json.decodeFromString(XacmlAuthorizationResponse.serializer(), body)

    @Test
    fun `decodes the PascalCase spelling used in Altinn's documentation`() {
        assertEquals("Permit", decode("""{"Response":[{"Decision":"Permit"}]}""").response?.single()?.decision)
    }

    @Test
    fun `decodes the camelCase spelling Altinn actually answers with`() {
        assertEquals("Permit", decode("""{"response":[{"decision":"Permit"}]}""").response?.single()?.decision)
    }

    @Test
    fun `ignores the fields the client does not model`() {
        val body = """
            {"Response":[{"Decision":"Deny","Status":{"StatusCode":{"Value":"urn:oasis:names:tc:xacml:1.0:status:ok"}},
            "Obligations":[],"PolicyIdentifierList":{"PolicyIdReference":[{"Id":"urn:altinn:policy:1"}]}}]}
        """.trimIndent()

        assertEquals("Deny", decode(body).response?.single()?.decision)
    }

    @Test
    fun `tolerates a response with no entries at all`() {
        assertNull(decode("{}").response)
        assertTrue(decode("""{"Response":[]}""").response!!.isEmpty())
    }

    @Test
    fun `tolerates an entry that carries no decision`() {
        assertNull(decode("""{"Response":[{}]}""").response?.single()?.decision)
    }

    @Test
    fun `decodes a real Permit from TT02, obligations and status included`() {
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

        val result = decode(body).response!!.single()

        assertEquals("Permit", result.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:ok", result.status?.statusCode?.value)
        val obligations = result.obligations!!
        assertEquals(2, obligations.size)
        val first = obligations.first().attributeAssignment!!.single()
        assertEquals("urn:altinn:minimum-authenticationlevel", first.category)
        assertEquals("3", first.value)
        val second = obligations[1].attributeAssignment!!.single()
        assertEquals("urn:altinn:minimum-authenticationlevel-org", second.category)
    }

    @Test
    fun `decodes a real Indeterminate, keeping the processing-error status`() {
        val body = """
            {"response":[{"decision":"Indeterminate","status":{"statusMessage":null,"statusDetails":null,
            "statusCode":{"value":"urn:oasis:names:tc:xacml:1.0:status:processing-error","statusCode":null}},
            "obligations":null,"associateAdvice":null,"category":null,"policyIdentifierList":null}]}
        """.trimIndent().replace("\n", "")

        val result = decode(body).response!!.single()

        assertEquals("Indeterminate", result.decision)
        assertEquals("urn:oasis:names:tc:xacml:1.0:status:processing-error", result.status?.statusCode?.value)
        assertNull(result.obligations)
    }
}
