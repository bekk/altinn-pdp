package no.kartverket.altinnpdp.client

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
        Case("pid", { PersonId.parse(it).value }, "01817012309", "0181701230"),
        Case("resourceId", { ResourceId.parse(it).value }, "kartverket-eiendom", "ABC"),
        Case("customerOrganizationNumber", { OrganizationNumber.parse(it).value }, "923609016", "923609017"),
        Case("action", { ActionId.parse(it).value }, "custom-action", " "),
    )

    @Test
    fun `parse keeps a valid value and refuses an invalid or blank one`() {
        for (case in cases) {
            assertEquals(case.valid, case.parse(case.valid))
            assertFailsWith<IllegalArgumentException>(case.field) { case.parse(case.invalid) }
            assertFailsWith<IllegalArgumentException>(case.field) { case.parse("") }
        }
    }

    @Test
    fun `a PersonId only checks that the number has 11 digits`() {
        assertEquals("31027012356", PersonId.parse("31027012356").value)
    }

    @Test
    fun `a PersonId never prints the number, while a SystemUserId prints as is`() {
        val subject: PdpSubject = PersonId.parse("01817012309")

        assertEquals("PersonId(***********)", PersonId.parse("01817012309").toString())
        assertEquals("PersonId(***********)", "$subject")
        assertContains("${SystemUserId.parse("1725580f-70f4-4ace-a748-4f912497a0d7")}", "1725580f-70f4-4ace-a748-4f912497a0d7")
    }
}
