package no.kartverket.altinnpdp.client

import kotlinx.serialization.SerializationException
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpRequest
import no.kartverket.altinnpdp.client.http.PdpHttpResponse
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationRequest
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationResponse
import java.net.URI

public class PdpClient internal constructor(
    platformBaseUrl: String,
    private val tokenProvider: AltinnTokenProvider,
    private val subscriptionKey: String,
    private val httpClient: PdpHttpClient,
) {
    public constructor(
        environment: AltinnEnvironment,
        subscriptionKey: String,
        maskinportenClientId: String,
        maskinportenKey: MaskinportenKey,
        httpClient: PdpHttpClient,
    ) : this(
        platformBaseUrl = environment.platformBaseUrl,
        tokenProvider = MaskinportenAltinnTokenProvider(
            MaskinportenConfig(environment.maskinportenTokenUrl, maskinportenClientId, maskinportenKey),
            environment,
            httpClient,
        ),
        subscriptionKey = subscriptionKey,
        httpClient = httpClient,
    )

    private val authorizeUrl: URI = Http.url(platformBaseUrl, AUTHORIZE_PATH)

    public suspend fun authorize(
        systemuserId: SystemUserId,
        resourceId: ResourceId,
        customerOrganizationNumber: OrganizationNumber,
        action: ActionId,
    ): PdpAuthorization = fetchAuthorization(systemuserId, resourceId, customerOrganizationNumber, action)

    private suspend fun fetchAuthorization(
        subject: SystemUserId,
        resource: ResourceId,
        org: OrganizationNumber,
        actionId: ActionId,
    ): PdpAuthorization {
        val token = tokenProvider.getAltinnToken()
        val body = Http.json.encodeToString(
            XacmlAuthorizationRequest.serializer(),
            XacmlAuthorizationRequest.forSystemUser(subject, resource, org, actionId),
        )
        val request = PdpHttpRequest(
            method = "POST",
            url = authorizeUrl,
            headers = mapOf(
                "Authorization" to "Bearer ${token.value}",
                SUBSCRIPTION_KEY_HEADER to subscriptionKey,
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = body,
        )

        val response = Http.sendExpectingOk(httpClient, request, "Altinn PDP", ::PdpException)
        return authorizationOf(response)
    }

    private fun authorizationOf(response: PdpHttpResponse): PdpAuthorization {
        fun unusable(message: String, cause: Throwable? = null) =
            PdpException(message, response.statusCode, response.body, cause)

        val parsed = try {
            Http.json.decodeFromString(XacmlAuthorizationResponse.serializer(), response.body)
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
            statusCode = result.status?.statusCode?.value,
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

    internal companion object {
        const val AUTHORIZE_PATH = "/authorization/api/v1/authorize"

        const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }
}
