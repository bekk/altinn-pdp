package no.kartverket.altinnpdp.restserver

import no.bekk.bekkopen.org.OrganisasjonsnummerValidator
import no.bekk.bekkopen.person.FodselsnummerValidator
import no.kartverket.altinnpdp.restserver.models.FieldError
import no.kartverket.altinnpdp.restserver.models.ValidationCode
import kotlin.uuid.Uuid

internal class InvalidRequestException(val errors: List<FieldError>) : RuntimeException(errors.joinToString("; ") { it.message })

internal object PdpRequestValidation {

    const val RESOURCE_ID_PATTERN: String = "^[a-z0-9_-]{4,}$"

    private val RESOURCE_ID_FORMAT = Regex(RESOURCE_ID_PATTERN)

    const val ORGANIZATION_NUMBER_PATTERN: String = "^[0-9]{9}$"

    private val ORGANIZATION_NUMBER_FORMAT = Regex(ORGANIZATION_NUMBER_PATTERN)

    const val PID_PATTERN: String = "^[0-9]{11}$"

    private val PID_FORMAT = Regex(PID_PATTERN)

    fun validate(
        systemuserId: String?,
        pid: String?,
        resourceId: String?,
        customerOrganizationNumber: String?,
        action: String?,
    ): List<FieldError> = subjectErrors(systemuserId, pid) + listOfNotNull(
        resourceIdError(resourceId),
        organizationNumberError(customerOrganizationNumber),
        actionError(action),
    )

    private fun subjectErrors(systemuserId: String?, pid: String?): List<FieldError> {
        val hasSystemuserId = !systemuserId.isNullOrBlank()
        val hasPid = !pid.isNullOrBlank()
        return when {
            hasSystemuserId && hasPid -> bothSubjectFields(ValidationCode.CONFLICTING, "send systemuserId or pid, not both")
            hasSystemuserId -> listOfNotNull(systemuserIdError(systemuserId))
            hasPid -> listOfNotNull(pidError(pid))
            else -> bothSubjectFields(ValidationCode.MISSING, "systemuserId or pid is required")
        }
    }

    private fun bothSubjectFields(code: ValidationCode, message: String): List<FieldError> =
        listOf("systemuserId", "pid").map { FieldError(it, code, message) }

    private fun systemuserIdError(value: String?): FieldError? =
        fieldError(value, "systemuserId") { if (Uuid.parseHexDashOrNull(it) == null) "must be a UUID" else null }

    private fun pidError(value: String?): FieldError? =
        fieldError(value, "pid") {
            when {
                !it.matches(PID_FORMAT) -> "must be exactly 11 digits"
                !FodselsnummerValidator.isValid(it) -> "must be a valid fødselsnummer or D number"
                else -> null
            }
        }

    private fun resourceIdError(value: String?): FieldError? =
        fieldError(value, "resourceId") {
            if (it.matches(RESOURCE_ID_FORMAT)) {
                null
            } else {
                "must be at least 4 characters of lowercase letters, digits, underscore or hyphen"
            }
        }

    private fun organizationNumberError(value: String?): FieldError? =
        fieldError(value, "customerOrganizationNumber") {
            when {
                !it.matches(ORGANIZATION_NUMBER_FORMAT) -> "must be exactly 9 digits"
                !OrganisasjonsnummerValidator.isValid(it) -> "must have a valid MOD11 check digit"
                else -> null
            }
        }

    private fun actionError(value: String?): FieldError? =
        fieldError(value, "action") { null }

    private inline fun fieldError(
        value: String?,
        field: String,
        problem: (String) -> String?,
    ): FieldError? {
        if (value.isNullOrBlank()) {
            return FieldError(field, ValidationCode.MISSING, "$field is required")
        }
        return problem(value)?.let { FieldError(field, ValidationCode.INVALID_FORMAT, "$field $it") }
    }
}
