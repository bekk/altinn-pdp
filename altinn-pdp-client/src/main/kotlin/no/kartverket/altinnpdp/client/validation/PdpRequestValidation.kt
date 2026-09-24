package no.kartverket.altinnpdp.client.validation

import kotlin.uuid.Uuid

enum class PdpValidationCode {
    MISSING,
    INVALID_FORMAT,
}

data class PdpValidationError(
    val field: String,
    val code: PdpValidationCode,
    val message: String,
)

/** The formats are Altinn's own, not ours: they reject all of these upstream already. */
object PdpRequestValidation {

    val RESOURCE_ID_FORMAT = Regex("^[a-z0-9_-]{4,}$")

    val ORGANIZATION_NUMBER_FORMAT = Regex("^[0-9]{9}$")

    private val MOD11_WEIGHTS = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2)

    fun validate(
        systemuserId: String?,
        resourceId: String?,
        customerOrganizationNumber: String?,
        action: String?,
    ): List<PdpValidationError> = listOfNotNull(
        systemuserIdError(systemuserId),
        resourceIdError(resourceId),
        organizationNumberError(customerOrganizationNumber),
        actionError(action),
    )

    internal fun systemuserIdError(value: String?): PdpValidationError? =
        fieldError(value, "systemuserId") { if (Uuid.parseHexDashOrNull(it) == null) "must be a UUID" else null }

    internal fun resourceIdError(value: String?): PdpValidationError? =
        fieldError(value, "resourceId") {
            if (it.matches(RESOURCE_ID_FORMAT)) {
                null
            } else {
                "must be at least 4 characters of lowercase letters, digits, underscore or hyphen"
            }
        }

    internal fun organizationNumberError(value: String?): PdpValidationError? =
        fieldError(value, "customerOrganizationNumber") {
            when {
                !it.matches(ORGANIZATION_NUMBER_FORMAT) -> "must be exactly 9 digits"
                !hasValidMod11(it) -> "must have a valid MOD11 check digit"
                else -> null
            }
        }

    internal fun actionError(value: String?): PdpValidationError? =
        fieldError(value, "action") { null }

    fun hasValidMod11(customerOrganizationNumber: String): Boolean {
        if (!customerOrganizationNumber.matches(ORGANIZATION_NUMBER_FORMAT)) return false
        val sum = MOD11_WEIGHTS.indices.sumOf { (customerOrganizationNumber[it] - '0') * MOD11_WEIGHTS[it] }
        val remainder = sum % 11
        val control = if (remainder == 0) 0 else 11 - remainder
        return control != 10 && control == customerOrganizationNumber[8] - '0'
    }

    private inline fun fieldError(
        value: String?,
        field: String,
        problem: (String) -> String?,
    ): PdpValidationError? {
        if (value.isNullOrBlank()) {
            return PdpValidationError(field, PdpValidationCode.MISSING, "$field is required")
        }
        return problem(value)?.let { PdpValidationError(field, PdpValidationCode.INVALID_FORMAT, "$field $it") }
    }
}
