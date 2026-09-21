package no.kartverket.altinnpdp.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Altinn answers in camelCase; the PascalCase of their documentation is accepted too. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class XacmlAuthorizationResponse(
    @JsonNames("Response") val response: List<Result>? = null,
) {
    @Serializable
    data class Result(
        @JsonNames("Decision") val decision: String? = null,
        @JsonNames("Status") val status: Status? = null,
        @JsonNames("Obligations") val obligations: List<Obligation>? = null,
    )

    @Serializable
    data class Status(
        @JsonNames("StatusMessage") val statusMessage: String? = null,
        @JsonNames("StatusCode") val statusCode: StatusCode? = null,
    )

    @Serializable
    data class StatusCode(
        @JsonNames("Value") val value: String? = null,
    )

    @Serializable
    data class Obligation(
        @JsonNames("Id") val id: String? = null,
        @JsonNames("AttributeAssignment") val attributeAssignment: List<AttributeAssignment>? = null,
    )

    @Serializable
    data class AttributeAssignment(
        @JsonNames("AttributeId") val attributeId: String? = null,
        @JsonNames("Value") val value: String? = null,
        @JsonNames("Category") val category: String? = null,
        @JsonNames("DataType") val dataType: String? = null,
    )
}
