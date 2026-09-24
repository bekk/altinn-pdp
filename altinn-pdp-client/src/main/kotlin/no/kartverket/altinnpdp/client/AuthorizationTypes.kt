package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation

@JvmInline
value class SystemUserId(val value: String) {
    init {
        PdpRequestValidation.systemuserIdError(value)?.let { throw PdpValidationException(listOf(it)) }
    }
}

@JvmInline
value class ResourceId(val value: String) {
    init {
        PdpRequestValidation.resourceIdError(value)?.let { throw PdpValidationException(listOf(it)) }
    }
}

@JvmInline
value class OrganizationNumber(val value: String) {
    init {
        PdpRequestValidation.organizationNumberError(value)?.let { throw PdpValidationException(listOf(it)) }
    }
}

@JvmInline
value class ActionId(val value: String) {
    init {
        PdpRequestValidation.actionError(value)?.let { throw PdpValidationException(listOf(it)) }
    }
}
