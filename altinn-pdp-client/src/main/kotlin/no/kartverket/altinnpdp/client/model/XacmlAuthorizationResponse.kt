package no.kartverket.altinnpdp.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class XacmlAuthorizationResponse(
    @JsonNames("Response") val response: List<Result>? = null,
) {
    @Serializable
    data class Result(
        @JsonNames("Decision") val decision: String? = null,
    )
}
