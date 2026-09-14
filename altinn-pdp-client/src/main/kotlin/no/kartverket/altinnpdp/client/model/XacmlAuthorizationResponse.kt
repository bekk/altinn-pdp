package no.kartverket.altinnpdp.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Altinn's XACML JSON profile is PascalCase, but some environments/spec versions answer
 * camelCase - [JsonNames] accepts both spellings on decode so the decision isn't silently lost
 * either way.
 */
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
