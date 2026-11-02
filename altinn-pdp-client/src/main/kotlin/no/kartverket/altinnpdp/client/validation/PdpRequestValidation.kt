package no.kartverket.altinnpdp.client.validation

import no.bekk.bekkopen.org.OrganisasjonsnummerValidator
import no.bekk.bekkopen.person.FodselsnummerValidator
import kotlin.uuid.Uuid

public enum class ValidationCode {
    MISSING,
    INVALID_FORMAT,
    CONFLICTING,
}

public data class ValidationError(
    val field: String,
    val code: ValidationCode,
    val message: String,
)

public object PdpRequestValidation {

    public const val RESOURCE_ID_PATTERN: String = "^[a-z0-9_-]{4,}$"

    public val RESOURCE_ID_FORMAT: Regex = Regex(RESOURCE_ID_PATTERN)

    public const val ORGANIZATION_NUMBER_PATTERN: String = "^[0-9]{9}$"

    public val ORGANIZATION_NUMBER_FORMAT: Regex = Regex(ORGANIZATION_NUMBER_PATTERN)

    public const val PID_PATTERN: String = "^[0-9]{11}$"

    public val PID_FORMAT: Regex = Regex(PID_PATTERN)

    public const val RESOURCE_ATTRIBUTE_TYPE_PATTERN: String = "^urn:[a-z_-]+(:[a-z_-]+)?$"

    private val RESOURCE_ATTRIBUTE_TYPE_FORMAT = Regex(RESOURCE_ATTRIBUTE_TYPE_PATTERN)

    public const val RESOURCE_ATTRIBUTE_ID_PATTERN: String = "^[^:]+$"

    private const val ALTINN_NAMESPACE = "urn:altinn:"

    private const val ALTINN_SUBRESOURCE = "urn:altinn:subresource"

    public fun validate(
        systemuserId: String?,
        pid: String?,
        resourceId: String?,
        customerOrganizationNumber: String?,
        action: String?,
    ): List<ValidationError> = subjectErrors(systemuserId, pid) + listOfNotNull(
        resourceIdError(resourceId),
        organizationNumberError(customerOrganizationNumber),
        actionError(action),
    )

    public fun validateResourceAttributes(typesAndIds: List<Pair<String?, String?>>): List<ValidationError> {
        val repeatedTypes = typesAndIds.mapNotNull { it.first }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return typesAndIds.flatMapIndexed { index, (type, id) ->
            val typeField = "resourceAttributes[$index].type"
            val typeError = resourceAttributeTypeError(typeField, type) ?: if (type in repeatedTypes) {
                ValidationError(typeField, ValidationCode.CONFLICTING, "send each type only once")
            } else {
                null
            }
            listOfNotNull(typeError, resourceAttributeIdError("resourceAttributes[$index].id", id))
        }
    }

    private fun subjectErrors(systemuserId: String?, pid: String?): List<ValidationError> {
        val hasSystemuserId = !systemuserId.isNullOrBlank()
        val hasPid = !pid.isNullOrBlank()
        return when {
            hasSystemuserId && hasPid -> bothSubjectFields(ValidationCode.CONFLICTING, "send systemuserId or pid, not both")
            hasSystemuserId -> listOfNotNull(systemuserIdError(systemuserId))
            hasPid -> listOfNotNull(pidError(pid))
            else -> bothSubjectFields(ValidationCode.MISSING, "systemuserId or pid is required")
        }
    }

    private fun bothSubjectFields(code: ValidationCode, message: String): List<ValidationError> =
        listOf("systemuserId", "pid").map { ValidationError(it, code, message) }

    internal fun systemuserIdError(value: String?): ValidationError? =
        fieldError(value, "systemuserId") { if (Uuid.parseHexDashOrNull(it) == null) "must be a UUID" else null }

    internal fun pidError(value: String?): ValidationError? =
        fieldError(value, "pid") {
            when {
                !it.matches(PID_FORMAT) -> "must be exactly 11 digits"
                !FodselsnummerValidator.isValid(it) -> "must be a valid fødselsnummer or D number"
                else -> null
            }
        }

    internal fun resourceIdError(value: String?): ValidationError? =
        fieldError(value, "resourceId") {
            if (it.matches(RESOURCE_ID_FORMAT)) {
                null
            } else {
                "must be at least 4 characters of lowercase letters, digits, underscore or hyphen"
            }
        }

    internal fun organizationNumberError(value: String?): ValidationError? =
        fieldError(value, "customerOrganizationNumber") {
            when {
                !it.matches(ORGANIZATION_NUMBER_FORMAT) -> "must be exactly 9 digits"
                !OrganisasjonsnummerValidator.isValid(it) -> "must have a valid MOD11 check digit"
                else -> null
            }
        }

    internal fun actionError(value: String?): ValidationError? =
        fieldError(value, "action") { null }

    internal fun resourceAttributeTypeError(field: String, value: String?): ValidationError? =
        fieldError(value, field) {
            when {
                !it.matches(RESOURCE_ATTRIBUTE_TYPE_FORMAT) ->
                    "must be urn:<name> or urn:<name>:<name>, with names of lowercase letters, hyphen or underscore"

                it.startsWith(ALTINN_NAMESPACE) && it != ALTINN_SUBRESOURCE ->
                    "must not start with $ALTINN_NAMESPACE, other than $ALTINN_SUBRESOURCE"

                else -> null
            }
        }

    internal fun resourceAttributeIdError(field: String, value: String?): ValidationError? =
        fieldError(value, field) { if (':' in it) "must not contain a colon" else null }

    private inline fun fieldError(
        value: String?,
        field: String,
        problem: (String) -> String?,
    ): ValidationError? {
        if (value.isNullOrBlank()) {
            return ValidationError(field, ValidationCode.MISSING, "$field is required")
        }
        return problem(value)?.let { ValidationError(field, ValidationCode.INVALID_FORMAT, "$field $it") }
    }
}
