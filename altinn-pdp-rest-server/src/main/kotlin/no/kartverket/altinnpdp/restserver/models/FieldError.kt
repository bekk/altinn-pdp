package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.validation.PdpValidationCode

@Serializable
data class FieldError(val field: String, val code: PdpValidationCode, val message: String)
