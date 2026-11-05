package no.kartverket.altinnpdp.client

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizationTypesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `parse keeps a valid value and refuses an invalid or blank one`(
        field: String,
        parse: (String) -> String,
        valid: String,
        invalid: String,
    ) {
        assertEquals(valid, parse(valid))
        assertFailsWith<IllegalArgumentException>(field) { parse(invalid) }
        assertFailsWith<IllegalArgumentException>(field) { parse("") }
    }

    @Test
    fun `a ResourceAttribute takes the type and id rows of Altinn Studio's policy editor`() {
        val attribute = ResourceAttribute.parse("urn:kv:geonorge", "dataset")
        assertEquals("urn:kv:geonorge", attribute.type)
        assertEquals("dataset", attribute.id)
        assertEquals("metadata", ResourceAttribute.parse("urn:altinn:subresource", "metadata").id)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["urn:geonorge", "urn:kv-geo:data_set"])
    fun `a ResourceAttribute takes a type of one or two names`(type: String) {
        assertEquals(type, ResourceAttribute.parse(type, "dataset").type)
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "kv:geonorge | no urn: prefix, which the editor would have added",
            "URN:kv:geonorge | the editor only recognises a lowercase urn: prefix",
            "urn:kv geonorge | whitespace",
            "urn:kv: geonorge | an em space",
            "urn:kv geonorge | a non-breaking space",
            "urn:kv:Geonorge | an uppercase letter",
            "urn:kv2:geonorge | a digit",
            "urn:kv.geonorge | a dot",
            "urn:kv: | an empty name after a colon",
            "urn::geonorge | an empty name between colons",
            "urn:kv:geonorge:dataset | more than two names after urn:",
            "urn: | nothing after urn:",
            "urn:altinn:resource | sent from resourceId",
            "urn:altinn:organization:identifier-no | sent from customerOrganizationNumber",
            "urn:ALTINN:resource | urn:altinn: in another case",
        ],
    )
    fun `a ResourceAttribute refuses a type outside urn or inside urn altinn`(type: String, why: String) {
        assertFailsWith<IllegalArgumentException>(why) { ResourceAttribute.parse(type, "dataset") }
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["", " ", "abc:123"])
    fun `a ResourceAttribute refuses a blank id or one with a colon`(id: String) {
        assertFailsWith<IllegalArgumentException> { ResourceAttribute.parse("urn:kv:geonorge", id) }
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

    companion object {
        @JvmStatic
        fun parsers(): List<Arguments> = listOf(
            Arguments.of("systemuserId", { value: String -> SystemUserId.parse(value).value }, "1725580F-70F4-4ACE-A748-4F912497A0D7", "1-1-1-1-1"),
            Arguments.of("pid", { value: String -> PersonId.parse(value).value }, "01817012309", "0181701230"),
            Arguments.of("resourceId", { value: String -> ResourceId.parse(value).value }, "kartverket-eiendom", "ABC"),
            Arguments.of("customerOrganizationNumber", { value: String -> OrganizationNumber.parse(value).value }, "923609016", "923609017"),
            Arguments.of("action", { value: String -> ActionId.parse(value).value }, "custom-action", " "),
        )
    }
}
