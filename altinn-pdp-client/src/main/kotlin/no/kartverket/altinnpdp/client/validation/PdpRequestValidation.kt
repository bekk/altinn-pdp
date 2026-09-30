package no.kartverket.altinnpdp.client.validation

import kotlin.uuid.Uuid

public enum class PdpValidationCode {
    MISSING,
    INVALID_FORMAT,
    CONFLICTING,
}

public data class PdpValidationError(
    val field: String,
    val code: PdpValidationCode,
    val message: String,
)

public object PdpRequestValidation {

    public val RESOURCE_ID_FORMAT: Regex = Regex("^[a-z0-9_-]{4,}$")

    public val ORGANIZATION_NUMBER_FORMAT: Regex = Regex("^[0-9]{9}$")

    public val PID_FORMAT: Regex = Regex("^[0-9]{11}$")

    private val MOD11_WEIGHTS = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2)

    private val PID_FIRST_CHECK_WEIGHTS = intArrayOf(3, 7, 6, 1, 8, 9, 4, 5, 2, 1)

    private val PID_SECOND_CHECK_WEIGHTS = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2, 1)

    private val PID_FIRST_CHECK_REMAINDERS_FROM_2032 = 0..3

    public fun validate(
        systemuserId: String?,
        pid: String?,
        resourceId: String?,
        customerOrganizationNumber: String?,
        action: String?,
    ): List<PdpValidationError> = subjectErrors(systemuserId, pid) + listOfNotNull(
        resourceIdError(resourceId),
        organizationNumberError(customerOrganizationNumber),
        actionError(action),
    )

    private fun subjectErrors(systemuserId: String?, pid: String?): List<PdpValidationError> {
        val hasSystemuserId = !systemuserId.isNullOrBlank()
        val hasPid = !pid.isNullOrBlank()
        return when {
            hasSystemuserId && hasPid -> bothSubjectFields(PdpValidationCode.CONFLICTING, "send systemuserId or pid, not both")
            hasSystemuserId -> listOfNotNull(systemuserIdError(systemuserId))
            hasPid -> listOfNotNull(pidError(pid))
            else -> bothSubjectFields(PdpValidationCode.MISSING, "systemuserId or pid is required")
        }
    }

    private fun bothSubjectFields(code: PdpValidationCode, message: String): List<PdpValidationError> =
        listOf("systemuserId", "pid").map { PdpValidationError(it, code, message) }

    internal fun systemuserIdError(value: String?): PdpValidationError? =
        fieldError(value, "systemuserId") { if (Uuid.parseHexDashOrNull(it) == null) "must be a UUID" else null }

    internal fun pidError(value: String?): PdpValidationError? =
        fieldError(value, "pid") {
            when {
                !it.matches(PID_FORMAT) -> "must be exactly 11 digits"
                !hasValidPidCheckDigits(it) -> "must have valid check digits"
                else -> null
            }
        }

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

    internal fun hasValidMod11(customerOrganizationNumber: String): Boolean {
        if (!customerOrganizationNumber.matches(ORGANIZATION_NUMBER_FORMAT)) return false
        val remainder = weightedSum(customerOrganizationNumber, MOD11_WEIGHTS) % 11
        val control = if (remainder == 0) 0 else 11 - remainder
        return control != 10 && control == customerOrganizationNumber[8] - '0'
    }

    private fun hasValidPidCheckDigits(pid: String): Boolean =
        weightedSum(pid, PID_FIRST_CHECK_WEIGHTS) % 11 in PID_FIRST_CHECK_REMAINDERS_FROM_2032 &&
            weightedSum(pid, PID_SECOND_CHECK_WEIGHTS) % 11 == 0

    private fun weightedSum(digits: String, weights: IntArray): Int =
        weights.indices.sumOf { (digits[it] - '0') * weights[it] }

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
