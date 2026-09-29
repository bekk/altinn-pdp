package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizationTypesTest {

    private class Case(
        val field: String,
        val parse: (String) -> String,
        val valid: String,
        val invalid: String,
    )

    private val cases = listOf(
        Case("systemuserId", { SystemUserId.parse(it).value }, "1725580F-70F4-4ACE-A748-4F912497A0D7", "1-1-1-1-1"),
        Case("pid", { PersonId.parse(it).value }, "31827012311", "31027012357"),
        Case("resourceId", { ResourceId.parse(it).value }, "kartverket-eiendom", "ABC"),
        Case("organizationNumber", { OrganizationNumber.parse(it).value }, "923609016", "923609017"),
        Case("action", { ActionId.parse(it).value }, "custom-action", " "),
    )

    @Test
    fun `parse keeps a valid value and throws with the reason for an invalid one`() {
        for (case in cases) {
            assertEquals(case.valid, case.parse(case.valid))
            val invalid = assertFailsWith<PdpValidationException>(case.field) { case.parse(case.invalid) }
            assertEquals(case.field, invalid.errors.single().field)
            val blank = assertFailsWith<PdpValidationException>(case.field) { case.parse("") }
            assertEquals(PdpValidationCode.MISSING, blank.errors.single().code)
        }
    }

    @Test
    fun `a PersonId never prints the number, while a SystemUserId prints as is`() {
        val subject: PdpSubject = PersonId.parse("31827012311")

        assertEquals("PersonId(***********)", PersonId.parse("31827012311").toString())
        assertEquals("PersonId(***********)", "$subject")
        assertContains("${SystemUserId.parse("1725580f-70f4-4ace-a748-4f912497a0d7")}", "1725580f-70f4-4ace-a748-4f912497a0d7")
    }
}
