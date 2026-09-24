package no.kartverket.altinnpdp.client.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdpRequestValidationTest {

    private val uuid = "1725580f-70f4-4ace-a748-4f912497a0d7"

    private fun validate(
        systemuserId: String? = uuid,
        resourceId: String? = "fleks-pdp-demo",
        customerOrganizationNumber: String? = "311718371",
        action: String? = "read",
    ) = PdpRequestValidation.validate(systemuserId, resourceId, customerOrganizationNumber, action)

    @Test
    fun `a valid request produces no errors`() {
        assertTrue(validate().isEmpty())
    }

    @Test
    fun `every missing field is reported, not just the first`() {
        val errors = validate(systemuserId = null, resourceId = null, customerOrganizationNumber = null, action = null)

        assertEquals(listOf("systemuserId", "resourceId", "customerOrganizationNumber", "action"), errors.map { it.field })
        assertTrue(errors.all { it.code == PdpValidationCode.MISSING })
    }

    @Test
    fun `a blank field counts as missing`() {
        val errors = validate(action = "   ")

        assertEquals(PdpValidationCode.MISSING, errors.single().code)
        assertEquals("action", errors.single().field)
    }

    @Test
    fun `several format errors are reported together`() {
        val errors = validate(systemuserId = "not-a-uuid", resourceId = "ab", customerOrganizationNumber = "12345")

        assertEquals(3, errors.size)
        assertTrue(errors.all { it.code == PdpValidationCode.INVALID_FORMAT })
    }

    @Test
    fun `systemuserId must be a uuid`() {
        assertTrue(validate(systemuserId = "not-a-uuid").isNotEmpty())
        assertTrue(validate(systemuserId = "1725580f70f44acea7484f912497a0d7").isNotEmpty())
        assertTrue(validate(systemuserId = uuid.uppercase()).isEmpty())
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
    fun `MOD11 is a typo check, not an existence check`() {
        assertTrue(PdpRequestValidation.hasValidMod11("000000000"))
        assertTrue(PdpRequestValidation.hasValidMod11("999999999"))
    }

    @Test
    fun `action is only checked for presence`() {
        assertTrue(validate(action = "anything at all").isEmpty())
    }
}
