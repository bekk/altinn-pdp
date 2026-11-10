package no.kartverket.altinnpdp.restserver.authorize

import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.restserver.problem.FieldError
import no.kartverket.altinnpdp.restserver.problem.ValidationCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import no.kartverket.altinnpdp.client.ResourceAttribute as PdpResourceAttribute

/** The rules themselves are the client's, and tested there. This tests what the server adds to them. */
class PdpRequestValidationTest {

    init {
        configurePidValidation(AltinnEnvironment.TT02)
    }

    private val uuid = "1725580f-70f4-4ace-a748-4f912497a0d7"

    private fun parse(
        systemuserId: String? = uuid,
        pid: String? = null,
        resourceId: String? = "fleks-pdp-demo",
        customerOrganizationNumber: String? = "311718371",
        action: String? = "read",
        resourceAttributes: List<Pair<String?, String?>> = emptyList(),
    ) = PdpRequestValidation.parse(systemuserId, pid, resourceId, customerOrganizationNumber, action, resourceAttributes)

    private fun validate(
        systemuserId: String? = uuid,
        pid: String? = null,
        resourceId: String? = "fleks-pdp-demo",
        customerOrganizationNumber: String? = "311718371",
        action: String? = "read",
        resourceAttributes: List<Pair<String?, String?>> = emptyList(),
    ): List<FieldError> = try {
        parse(systemuserId, pid, resourceId, customerOrganizationNumber, action, resourceAttributes)
        emptyList()
    } catch (e: InvalidRequestException) {
        e.errors
    }

    @Test
    fun `a valid request is parsed into the client's types, with the resource attributes sorted by type`() {
        assertEquals(
            AuthorizeRequest.Parsed(
                SystemUserId.parse(uuid),
                ResourceId.parse("fleks-pdp-demo"),
                OrganizationNumber.parse("311718371"),
                ActionId.parse("read"),
                listOf(PdpResourceAttribute.parse("urn:altinn:subresource", "metadata"), PdpResourceAttribute.parse("urn:kv:geonorge", "dataset")),
            ),
            parse(resourceAttributes = listOf("urn:kv:geonorge" to "dataset", "urn:altinn:subresource" to "metadata")),
        )
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
    fun `a systemuserId the client refuses is reported as not a UUID`() {
        assertEquals(
            FieldError("systemuserId", ValidationCode.INVALID_FORMAT, "systemuserId must be a UUID"),
            validate(systemuserId = "not-a-uuid").single(),
        )
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
        assertTrue(errors.all { it.code == ValidationCode.CONFLICTING && it.message == "send systemuserId or pid, not both" })
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "01017012343 | a fødselsnummer",
            "41017012337 | a D number, with 40 added to the day",
            "01817012309 | a Tenor test person, with 80 added to the month",
        ],
    )
    fun `pid accepts fødselsnummer, D numbers and Tenor test persons`(pid: String, why: String) {
        assertTrue(validate(systemuserId = null, pid = pid).isEmpty(), why)
    }

    @Test
    fun `pid does not yet accept first check digits that are only valid from 2032`() {
        assertTrue(validate(systemuserId = null, pid = "01017012351").isNotEmpty())
    }

    @Test
    fun `a pid that is not 11 digits is reported as such`() {
        assertEquals("pid must be exactly 11 digits", validate(systemuserId = null, pid = "3102701235").single().message)
    }

    @Test
    fun `a pid with a bad check digit is reported as such`() {
        assertEquals(
            "pid must be a valid fødselsnummer or D number",
            validate(systemuserId = null, pid = "01017012344").single().message,
        )
    }

    @Test
    fun `a resourceId the client refuses is reported with the resource registry's rule`() {
        assertEquals(
            "resourceId must be at least 4 characters of lowercase letters, digits, underscore or hyphen",
            validate(resourceId = "abc").single().message,
        )
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

    /**
     * The reasons describe the client's rules in words, and the tests above give a value each reason refuses. These are
     * values at the edges of what the reasons allow, so a stricter client fails here instead of getting the wrong reason.
     * pid and action have tests of their own above.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "systemuserId | 00000000-0000-0000-0000-000000000000",
            "systemuserId | FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",
            "resourceId | abcd",
            "resourceId | ____",
            "resourceId | abcdefghijklmnopqrstuvwxyz0123456789_-",
            "customerOrganizationNumber | 100000008",
            "customerOrganizationNumber | 923609016",
            "type | urn:a",
            "type | urn:abcdefghijklmnopqrstuvwxyz_-:abcdefghijklmnopqrstuvwxyz_-",
            "type | urn:altinn",
            "type | urn:altinn-x:y",
            "type | urn:altinn:subresource",
            "id | -",
            "id | ' Datasett 1/ÆØÅ.* '",
        ],
    )
    fun `the client accepts what the reasons allow`(field: String, value: String) {
        val errors = when (field) {
            "systemuserId" -> validate(systemuserId = value)
            "resourceId" -> validate(resourceId = value)
            "customerOrganizationNumber" -> validate(customerOrganizationNumber = value)
            "type" -> validate(resourceAttributes = listOf(value to "dataset"))
            "id" -> validate(resourceAttributes = listOf("urn:kv:geonorge" to value))
            else -> error("No such field: $field")
        }
        assertEquals(emptyList(), errors)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedResourceAttributes")
    fun `a refused resource attribute is reported on the field the client refused, with the reason`(
        why: String,
        attributes: List<Pair<String?, String?>>,
        errors: List<FieldError>,
    ) {
        assertEquals(errors, validate(resourceAttributes = attributes), why)
    }

    companion object {
        private fun typeError(index: Int, code: ValidationCode, message: String) =
            FieldError("resourceAttributes[$index].type", code, "resourceAttributes[$index].type $message")

        private fun idError(index: Int, code: ValidationCode, message: String) =
            FieldError("resourceAttributes[$index].id", code, "resourceAttributes[$index].id $message")

        private const val NOT_A_URN = "must be urn:<name> or urn:<name>:<name>, with names of lowercase letters, hyphen or underscore"

        private const val UNDER_URN_ALTINN = "must not start with urn:altinn:, other than urn:altinn:subresource"

        @JvmStatic
        fun refusedResourceAttributes(): List<Arguments> = listOf(
            Arguments.of(
                "a type that is not a URN",
                listOf("kv:geonorge" to "dataset"),
                listOf(typeError(0, ValidationCode.INVALID_FORMAT, NOT_A_URN)),
            ),
            Arguments.of(
                "a type under urn:altinn:",
                listOf("urn:altinn:resource" to "dataset"),
                listOf(typeError(0, ValidationCode.INVALID_FORMAT, UNDER_URN_ALTINN)),
            ),
            Arguments.of(
                "an id with a colon",
                listOf("urn:kv:geonorge" to "abc:123"),
                listOf(idError(0, ValidationCode.INVALID_FORMAT, "must not contain a colon")),
            ),
            Arguments.of(
                "a blank id",
                listOf("urn:kv:geonorge" to " "),
                listOf(idError(0, ValidationCode.MISSING, "is required")),
            ),
            Arguments.of(
                "a refused type, also when the id is missing",
                listOf("urn:altinn:resource" to null),
                listOf(typeError(0, ValidationCode.INVALID_FORMAT, UNDER_URN_ALTINN), idError(0, ValidationCode.MISSING, "is required")),
            ),
            Arguments.of(
                "a refused id, also when the type is missing",
                listOf(null to "abc:123"),
                listOf(typeError(0, ValidationCode.MISSING, "is required"), idError(0, ValidationCode.INVALID_FORMAT, "must not contain a colon")),
            ),
            Arguments.of(
                "one type twice",
                listOf("urn:kv:geonorge" to "dataset", "urn:kv:geonorge" to "service"),
                listOf(
                    FieldError("resourceAttributes[0].type", ValidationCode.CONFLICTING, "send each type only once"),
                    FieldError("resourceAttributes[1].type", ValidationCode.CONFLICTING, "send each type only once"),
                ),
            ),
            Arguments.of(
                "several refused attributes, each on its own index",
                listOf(
                    "urn:kv:geonorge" to "dataset",
                    null to "abc:123",
                    "urn:altinn:resource" to "other-resource",
                    "urn:kv:geonorge" to "service",
                ),
                listOf(
                    FieldError("resourceAttributes[0].type", ValidationCode.CONFLICTING, "send each type only once"),
                    typeError(1, ValidationCode.MISSING, "is required"),
                    idError(1, ValidationCode.INVALID_FORMAT, "must not contain a colon"),
                    typeError(2, ValidationCode.INVALID_FORMAT, UNDER_URN_ALTINN),
                    FieldError("resourceAttributes[3].type", ValidationCode.CONFLICTING, "send each type only once"),
                ),
            ),
        )
    }
}
