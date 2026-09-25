package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation
import no.kartverket.altinnpdp.client.validation.PdpValidationError

@JvmInline
value class SystemUserId private constructor(val value: String) {
    companion object {
        fun parse(value: String): SystemUserId = SystemUserId(valid(value, PdpRequestValidation::systemuserIdError))

        fun parseOrNull(value: String): SystemUserId? =
            if (PdpRequestValidation.systemuserIdError(value) == null) SystemUserId(value) else null
    }
}

@JvmInline
value class ResourceId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ResourceId = ResourceId(valid(value, PdpRequestValidation::resourceIdError))

        fun parseOrNull(value: String): ResourceId? =
            if (PdpRequestValidation.resourceIdError(value) == null) ResourceId(value) else null
    }
}

@JvmInline
value class OrganizationNumber private constructor(val value: String) {
    companion object {
        fun parse(value: String): OrganizationNumber =
            OrganizationNumber(valid(value, PdpRequestValidation::organizationNumberError))

        fun parseOrNull(value: String): OrganizationNumber? =
            if (PdpRequestValidation.organizationNumberError(value) == null) OrganizationNumber(value) else null
    }
}

@JvmInline
value class ActionId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ActionId = ActionId(valid(value, PdpRequestValidation::actionError))

        fun parseOrNull(value: String): ActionId? =
            if (PdpRequestValidation.actionError(value) == null) ActionId(value) else null
    }
}

private fun valid(value: String, error: (String?) -> PdpValidationError?): String {
    error(value)?.let { throw PdpValidationException(listOf(it)) }
    return value
}
