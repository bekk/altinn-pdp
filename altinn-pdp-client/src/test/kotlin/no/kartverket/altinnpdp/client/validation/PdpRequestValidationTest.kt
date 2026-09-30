package no.kartverket.altinnpdp.client.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdpRequestValidationTest {

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
        assertTrue(errors.all { it.code == PdpValidationCode.MISSING })
    }

    @Test
    fun `a blank field counts as missing`() {
        val errors = validate(action = "   ")

        assertEquals(PdpValidationCode.MISSING, errors.single().code)
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
        assertTrue(validate(systemuserId = null, pid = "31827012311").isEmpty())
    }

    @Test
    fun `neither systemuserId nor pid is reported on both fields`() {
        val errors = validate(systemuserId = null)

        assertEquals(listOf("systemuserId", "pid"), errors.map { it.field })
        assertTrue(errors.all { it.code == PdpValidationCode.MISSING && it.message == "systemuserId or pid is required" })
    }

    @Test
    fun `both systemuserId and pid is reported as a conflict on both fields`() {
        val errors = validate(pid = "31827012311")

        assertEquals(listOf("systemuserId", "pid"), errors.map { it.field })
        assertTrue(errors.all { it.code == PdpValidationCode.CONFLICTING })
    }

    @Test
    fun `pid checks the check digits but not the date`() {
        val accepted = mapOf(
            "31027012356" to "a date that does not exist",
            "31827012311" to "a Tenor test person, with 80 added to the month",
            "71027012420" to "a D number, with 40 added to the day",
            "31027012445" to "a first check digit that is only valid from 2032",
        )
        for ((pid, why) in accepted) {
            assertTrue(validate(systemuserId = null, pid = pid).isEmpty(), why)
        }
    }

    @Test
    fun `a pid that is not 11 digits is reported as such`() {
        for (pid in listOf("3102701235", "310270123560", "3102701235a")) {
            assertEquals("pid must be exactly 11 digits", validate(systemuserId = null, pid = pid).single().message, pid)
        }
    }

    @Test
    fun `a pid with a bad check digit is reported as such`() {
        val cases = mapOf("31027012399" to "the first", "31027012357" to "the second")
        for ((pid, which) in cases) {
            assertEquals(
                "pid must have valid check digits",
                validate(systemuserId = null, pid = pid).single().message,
                "$which check digit",
            )
        }
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
    fun `MOD11 accepts real organisation numbers`() {
        assertTrue(PdpRequestValidation.hasValidMod11("923609016"))
        assertTrue(PdpRequestValidation.hasValidMod11("311718371"))
    }

    @Test
    fun `MOD11 rejects a transposed or altered digit`() {
        assertFalse(PdpRequestValidation.hasValidMod11("311718372"))
        assertFalse(PdpRequestValidation.hasValidMod11("987654321"))
    }

    @Test
    fun `action is only checked for presence`() {
        assertTrue(validate(action = "anything at all").isEmpty())
    }
}
