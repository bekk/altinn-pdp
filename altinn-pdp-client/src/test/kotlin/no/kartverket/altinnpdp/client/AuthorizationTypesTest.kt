package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizationTypesTest {
    @Test
    fun `value types preserve valid values and reject invalid construction`() {
        val constructors: List<Triple<String, (String) -> String, Pair<String, String>>> = listOf(
            Triple("systemuserId", { SystemUserId(it).value }, "1725580F-70F4-4ACE-A748-4F912497A0D7" to "1-1-1-1-1"),
            Triple("resourceId", { ResourceId(it).value }, "kartverket-eiendom" to "ABC"),
            Triple("customerOrganizationNumber", { OrganizationNumber(it).value }, "923609016" to "923609017"),
            Triple("action", { ActionId(it).value }, "custom-action" to " "),
        )
        for ((field, construct, values) in constructors) {
            assertEquals(values.first, construct(values.first))
            val invalid = assertFailsWith<PdpValidationException> { construct(values.second) }
            assertEquals(field, invalid.errors.single().field)
            val blank = assertFailsWith<PdpValidationException> { construct("") }
            assertEquals(PdpValidationCode.MISSING, blank.errors.single().code)
        }
    }
}
