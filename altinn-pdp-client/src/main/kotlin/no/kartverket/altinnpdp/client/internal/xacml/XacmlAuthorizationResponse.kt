package no.kartverket.altinnpdp.client.internal.xacml

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNames
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpException
import no.kartverket.altinnpdp.client.PdpObligation
import no.kartverket.altinnpdp.client.PdpStatus
import no.kartverket.altinnpdp.client.http.OutgoingResponse
import no.kartverket.altinnpdp.client.internal.http.Http

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
        @JsonNames("Value") val value: String? = null,
        @JsonNames("Category") val category: String? = null,
    )
}

internal fun authorizationOf(response: OutgoingResponse): PdpAuthorization {
    val body = response.body.decodeToString()

    fun unusable(message: String, cause: Throwable? = null) =
        PdpException(message, response.statusCode, body, cause)

    val parsed = try {
        Http.json.decodeFromString(XacmlAuthorizationResponse.serializer(), body)
    } catch (e: SerializationException) {
        throw unusable("Failed to parse the PDP response: ${e.message}", e)
    }
    val results = parsed.response.orEmpty()
    if (results.size > 1) {
        throw unusable("The PDP response had ${results.size} Response entries, but only one decision was requested")
    }
    val result = results.firstOrNull()
    val decision = result?.decision ?: throw unusable("The PDP response had no Response entries with a decision")
    val parsedDecision = try {
        PdpDecision.fromXacmlValue(decision)
    } catch (e: IllegalArgumentException) {
        throw unusable("Unknown XACML decision \"$decision\" in PDP response", e)
    }
    return PdpAuthorization(
        decision = parsedDecision,
        status = result.status?.statusCode?.value?.let(PdpStatus::of),
        obligations = obligationsOf(result),
    )
}

private fun obligationsOf(result: XacmlAuthorizationResponse.Result): List<PdpObligation> =
    result.obligations.orEmpty().flatMap { obligation ->
        obligation.attributeAssignment.orEmpty().mapNotNull { assignment ->
            val category = assignment.category ?: return@mapNotNull null
            val value = assignment.value ?: return@mapNotNull null
            PdpObligation(id = obligation.id, category = category, value = value)
        }
    }
