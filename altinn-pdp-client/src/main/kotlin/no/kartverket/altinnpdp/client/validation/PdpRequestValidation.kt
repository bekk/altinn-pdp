package no.kartverket.altinnpdp.client.validation

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

    val UUID_FORMAT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    val RESOURCE_ID_FORMAT = Regex("^[a-z0-9_-]{4,}$")

    val ORGANIZATION_NUMBER_FORMAT = Regex("^[0-9]{9}$")

    private val MOD11_WEIGHTS = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2)

    fun validate(
        systemuserId: String?,
        resourceId: String?,
        organizationNumber: String?,
        action: String?,
    ): List<PdpValidationError> = buildList {
        check(systemuserId, "systemuserId") {
            it.matches(UUID_FORMAT) to "must be a UUID"
        }
        check(resourceId, "resourceId") {
            it.matches(RESOURCE_ID_FORMAT) to
                "must be at least 4 characters of lowercase letters, digits, underscore or hyphen"
        }
        check(organizationNumber, "organizationNumber") {
            when {
                !it.matches(ORGANIZATION_NUMBER_FORMAT) -> false to "must be exactly 9 digits"
                !hasValidMod11(it) -> false to "must have a valid MOD11 check digit"
                else -> true to ""
            }
        }
        check(action, "action") { true to "" }
    }

    fun hasValidMod11(organizationNumber: String): Boolean {
        if (!organizationNumber.matches(ORGANIZATION_NUMBER_FORMAT)) return false
        val sum = MOD11_WEIGHTS.indices.sumOf { (organizationNumber[it] - '0') * MOD11_WEIGHTS[it] }
        val remainder = sum % 11
        val control = if (remainder == 0) 0 else 11 - remainder
        return control != 10 && control == organizationNumber[8] - '0'
    }

    private inline fun MutableList<PdpValidationError>.check(
        value: String?,
        field: String,
        rule: (String) -> Pair<Boolean, String>,
    ) {
        if (value.isNullOrBlank()) {
            add(PdpValidationError(field, PdpValidationCode.MISSING, "$field is required"))
            return
        }
        val (ok, message) = rule(value)
        if (!ok) add(PdpValidationError(field, PdpValidationCode.INVALID_FORMAT, "$field $message"))
    }
}
