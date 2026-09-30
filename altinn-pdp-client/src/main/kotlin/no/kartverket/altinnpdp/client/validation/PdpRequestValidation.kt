package no.kartverket.altinnpdp.client.validation

import no.bekk.bekkopen.org.OrganisasjonsnummerValidator
import no.bekk.bekkopen.person.FodselsnummerValidator
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

    init {
        FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS = true
    }

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
                !FodselsnummerValidator.isValid(it) -> "must be a valid fødselsnummer or D number"
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
                !OrganisasjonsnummerValidator.isValid(it) -> "must have a valid MOD11 check digit"
                else -> null
            }
        }

    internal fun actionError(value: String?): PdpValidationError? =
        fieldError(value, "action") { null }

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
