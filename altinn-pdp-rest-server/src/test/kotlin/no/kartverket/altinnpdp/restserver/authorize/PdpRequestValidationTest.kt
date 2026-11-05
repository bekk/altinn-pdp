package no.kartverket.altinnpdp.restserver.authorize

import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.restserver.problem.ValidationCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdpRequestValidationTest {

    init {
        configurePidValidation(AltinnEnvironment.TT02)
    }

    private val uuid = "1725580f-70f4-4ace-a748-4f912497a0d7"

    private fun validate(
        systemuserId: String? = uuid,
        pid: String? = null,
        resourceId: String? = "fleks-pdp-demo",
        customerOrganizationNumber: String? = "311718371",
        action: String? = "read",
    ) = PdpRequestValidation.validate(systemuserId, pid, resourceId, customerOrganizationNumber, action)

    @Test
    fun `a valid request produces no errors`() {
        assertTrue(validate().isEmpty())
    }

    @Test
    fun `every missing field is reported, not just the first`() {
        val errors = validate(systemuserId = null, resourceId = null, customerOrganizationNumber = null, action = null)

        assertEquals(listOf("systemuserId", "pid", "resourceId", "customerOrganizationNumber", "action"), errors.map { it.field })
        assertTrue(errors.all { it.code == ValidationCode.MISSING })
    }

    @Test
    fun `a blank field counts as missing`() {
        val errors = validate(action = "   ")

        assertEquals(ValidationCode.MISSING, errors.single().code)
        assertEquals("action", errors.single().field)
    }

    @Test
    fun `systemuserId must be a uuid`() {
        assertTrue(validate(systemuserId = "not-a-uuid").isNotEmpty())
        assertTrue(validate(systemuserId = "1725580f70f44acea7484f912497a0d7").isNotEmpty())
        assertTrue(validate(systemuserId = "1-1-1-1-1").isNotEmpty(), "java.util.UUID would accept this")
        assertTrue(validate(systemuserId = uuid.uppercase()).isEmpty())
    }

    @Test
    fun `a pid can stand in for systemuserId`() {
        assertTrue(validate(systemuserId = null, pid = "01017012343").isEmpty())
    }

    @Test
    fun `neither systemuserId nor pid is reported on both fields`() {
        val errors = validate(systemuserId = null)

        assertEquals(listOf("systemuserId", "pid"), errors.map { it.field })
        assertTrue(errors.all { it.code == ValidationCode.MISSING && it.message == "systemuserId or pid is required" })
    }

    @Test
    fun `both systemuserId and pid is reported as a conflict on both fields`() {
        val errors = validate(pid = "01017012343")

        assertEquals(listOf("systemuserId", "pid"), errors.map { it.field })
        assertTrue(errors.all { it.code == ValidationCode.CONFLICTING })
    }

    @Test
    fun `pid accepts fødselsnummer, D numbers and Tenor test persons`() {
        val accepted = mapOf(
            "01017012343" to "a fødselsnummer",
            "41017012337" to "a D number, with 40 added to the day",
            "01817012309" to "a Tenor test person, with 80 added to the month",
        )
        for ((pid, why) in accepted) {
            assertTrue(validate(systemuserId = null, pid = pid).isEmpty(), why)
        }
    }

    @Test
    fun `pid does not yet accept first check digits that are only valid from 2032`() {
        assertTrue(validate(systemuserId = null, pid = "01017012351").isNotEmpty())
    }

    @Test
    fun `a pid that is not 11 digits is reported as such`() {
        for (pid in listOf("3102701235", "310270123560", "3102701235a")) {
            assertEquals("pid must be exactly 11 digits", validate(systemuserId = null, pid = pid).single().message, pid)
        }
    }

    @Test
    fun `a pid with a bad check digit is reported as such`() {
        assertEquals(
            "pid must be a valid fødselsnummer or D number",
            validate(systemuserId = null, pid = "01017012344").single().message,
        )
    }

    @Test
    fun `resourceId follows the resource registry's own rule`() {
        assertTrue(validate(resourceId = "app_ttd_apps-test").isEmpty())
        assertTrue(validate(resourceId = "fleks-pdp-demo").isEmpty())
        assertTrue(validate(resourceId = "abc").isNotEmpty(), "under 4 characters")
        assertTrue(validate(resourceId = "FLEKS-PDP-DEMO").isNotEmpty(), "uppercase")
        assertTrue(validate(resourceId = "fleks pdp demo").isNotEmpty(), "space")
    }

    @Test
    fun `a customerOrganizationNumber of the wrong length is reported as such`() {
        assertEquals(
            "customerOrganizationNumber must be exactly 9 digits",
            validate(customerOrganizationNumber = "92360901").single().message,
        )
    }

    @Test
    fun `nine digits with a bad check digit is reported as a MOD11 failure`() {
        assertEquals(
            "customerOrganizationNumber must have a valid MOD11 check digit",
            validate(customerOrganizationNumber = "123456789").single().message,
        )
    }

    @Test
    fun `action is only checked for presence`() {
        assertTrue(validate(action = "anything at all").isEmpty())
    }

    @Test
    fun `refuses every resource attribute that no policy from the editor can have`() {
        assertTrue(PdpRequestValidation.validateResourceAttributes(listOf("urn:altinn:subresource" to "metadata")).isEmpty())
        val refused = mapOf(
            "no urn: prefix" to listOf("kv:geonorge" to "dataset"),
            "an uppercase URN:" to listOf("URN:kv:geonorge" to "dataset"),
            "whitespace" to listOf("urn:kv geonorge" to "dataset"),
            "nothing after urn:" to listOf("urn:" to "dataset"),
            "a type this API sends itself" to listOf("urn:altinn:resource" to "dataset"),
            "urn:altinn: in another case" to listOf("urn:ALTINN:resource" to "dataset"),
            "a blank id" to listOf("urn:kv:geonorge" to " "),
            "an id with a colon" to listOf("urn:kv:geonorge" to "abc:123"),
            "one type twice" to listOf("urn:kv:geonorge" to "dataset", "urn:kv:geonorge" to "service"),
        )
        for ((why, attributes) in refused) {
            assertTrue(PdpRequestValidation.validateResourceAttributes(attributes).isNotEmpty(), why)
        }
    }
}
