package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation
import no.kartverket.altinnpdp.client.validation.PdpValidationError

@JvmInline
public value class SystemUserId private constructor(public val value: String) {
    public companion object {
        public fun parse(value: String): SystemUserId = SystemUserId(valid(value, PdpRequestValidation::systemuserIdError))

        public fun parseOrNull(value: String): SystemUserId? =
            if (PdpRequestValidation.systemuserIdError(value) == null) SystemUserId(value) else null
    }
}

@JvmInline
public value class ResourceId private constructor(public val value: String) {
    public companion object {
        public fun parse(value: String): ResourceId = ResourceId(valid(value, PdpRequestValidation::resourceIdError))

        public fun parseOrNull(value: String): ResourceId? =
            if (PdpRequestValidation.resourceIdError(value) == null) ResourceId(value) else null
    }
}

@JvmInline
public value class OrganizationNumber private constructor(public val value: String) {
    public companion object {
        public fun parse(value: String): OrganizationNumber =
            OrganizationNumber(valid(value, PdpRequestValidation::organizationNumberError))

        public fun parseOrNull(value: String): OrganizationNumber? =
            if (PdpRequestValidation.organizationNumberError(value) == null) OrganizationNumber(value) else null
    }
}

@JvmInline
public value class ActionId private constructor(public val value: String) {
    public companion object {
        public fun parse(value: String): ActionId = ActionId(valid(value, PdpRequestValidation::actionError))

        public fun parseOrNull(value: String): ActionId? =
            if (PdpRequestValidation.actionError(value) == null) ActionId(value) else null
    }
}

private fun valid(value: String, error: (String?) -> PdpValidationError?): String {
    error(value)?.let { throw PdpValidationException(listOf(it)) }
    return value
}
