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
    fun `a ResourceAttribute takes the type and id rows of Altinn Studio's policy editor`() {
        val attribute = ResourceAttribute.parse("urn:kv:geonorge", "dataset")
        assertEquals("urn:kv:geonorge", attribute.type)
        assertEquals("dataset", attribute.id)
        assertEquals("metadata", ResourceAttribute.parse("urn:altinn:subresource", "metadata").id)
        for (type in listOf("urn:geonorge", "urn:kv-geo:data_set")) {
            assertEquals(type, ResourceAttribute.parse(type, "dataset").type)
        }
    }

    @Test
    fun `a ResourceAttribute refuses a type outside urn or inside urn altinn`() {
        val refused = mapOf(
            "kv:geonorge" to "no urn: prefix, which the editor would have added",
            "URN:kv:geonorge" to "the editor only recognises a lowercase urn: prefix",
            "urn:kv geonorge" to "whitespace",
            "urn:kv:\u2003geonorge" to "an em space",
            "urn:kv\u00A0geonorge" to "a non-breaking space",
            "urn:kv:Geonorge" to "an uppercase letter",
            "urn:kv2:geonorge" to "a digit",
            "urn:kv.geonorge" to "a dot",
            "urn:kv:" to "an empty name after a colon",
            "urn::geonorge" to "an empty name between colons",
            "urn:kv:geonorge:dataset" to "more than two names after urn:",
            "urn:" to "nothing after urn:",
            "urn:altinn:resource" to "sent from resourceId",
            "urn:altinn:organization:identifier-no" to "sent from customerOrganizationNumber",
            "urn:ALTINN:resource" to "urn:altinn: in another case",
        )
        for ((type, why) in refused) {
            assertFailsWith<IllegalArgumentException>(why) { ResourceAttribute.parse(type, "dataset") }
        }
    }

    @Test
    fun `a ResourceAttribute refuses a blank id or one with a colon`() {
        for (id in listOf("", " ", "abc:123")) {
            assertFailsWith<IllegalArgumentException>("\"$id\"") { ResourceAttribute.parse("urn:kv:geonorge", id) }
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
