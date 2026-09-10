package no.kartverket.altinnpdp.client.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The PDP request body is a contract with Altinn that cannot be checked by reading this code -
 * it has to be compared against Altinn's documentation. These tests freeze the shape that was
 * verified against it, so a later refactor cannot quietly change what goes over the wire.
 */
class XacmlAuthorizationRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = XacmlAuthorizationRequest.forSystemUser(
        systemuserId = "5f2c1a8e-0000-4000-8000-2b3c4d5e6f70",
        resourceId = "urn:altinn:resource:kartverket-eiendom",
        organizationNumber = "923609016",
        action = "read",
    )

    @Test
    fun `serializes to the exact body Altinn's PDP expects`() {
        val expected = """
            {"request":{"returnPolicyIdList":true,"accessSubject":[{"attribute":[{"attributeId":"urn:altinn:systemuser:uuid","value":"5f2c1a8e-0000-4000-8000-2b3c4d5e6f70"}]}],"action":[{"attribute":[{"attributeId":"urn:oasis:names:tc:xacml:1.0:action:action-id","value":"read"}]}],"resource":[{"attribute":[{"attributeId":"urn:altinn:resource","value":"urn:altinn:resource:kartverket-eiendom"},{"attributeId":"urn:altinn:organization:identifier-no","value":"923609016"}]}]}}
        """.trimIndent()

        assertEquals(expected, json.encodeToString(XacmlAuthorizationRequest.serializer(), request()))
    }

    @Test
    fun `puts the resource id and the organization number in one resource category`() {
        // Splitting these into two categories still serializes and still gets a 200 back, but the
        // PDP then evaluates a different question and the decision is wrong rather than failed.
        val resource = request().request.resource

        assertEquals(1, resource.size, "both attributes belong to a single resource category")
        assertEquals(
            listOf(
                XacmlAuthorizationRequest.ATTRIBUTE_RESOURCE to "urn:altinn:resource:kartverket-eiendom",
                XacmlAuthorizationRequest.ATTRIBUTE_ORGANIZATION_NUMBER to "923609016",
            ),
            resource.single().attribute.map { it.attributeId to it.value },
        )
    }

    @Test
    fun `puts the systemuser in accessSubject and the action in action`() {
        val request = request().request

        assertEquals(
            listOf(XacmlAuthorizationRequest.ATTRIBUTE_SYSTEMUSER_UUID to "5f2c1a8e-0000-4000-8000-2b3c4d5e6f70"),
            request.accessSubject.single().attribute.map { it.attributeId to it.value },
        )
        assertEquals(
            listOf(XacmlAuthorizationRequest.ATTRIBUTE_ACTION_ID to "read"),
            request.action.single().attribute.map { it.attributeId to it.value },
        )
    }
}
