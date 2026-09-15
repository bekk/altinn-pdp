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
    fun `decodes the PascalCase spelling from Altinn's XACML profile`() {
        assertEquals("Permit", decode("""{"Response":[{"Decision":"Permit"}]}""").response?.single()?.decision)
    }

    @Test
    fun `decodes the camelCase spelling some environments answer with`() {
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
}
