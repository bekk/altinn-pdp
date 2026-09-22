package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

@Serializable
data class FieldError(val field: String, val code: String, val message: String)
