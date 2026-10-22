package no.kartverket.altinnpdp.client

import kotlinx.serialization.SerializationException
import no.kartverket.altinnpdp.client.auth.AccessTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.http.OutgoingResponse
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationRequest
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationResponse
import java.net.URI

public class PdpClient internal constructor(
    platformBaseUrl: String,
    private val tokenProvider: AccessTokenProvider,
    private val subscriptionKey: String,
    private val httpClient: OutgoingHttpClient,
) {
    public constructor(
        environment: AltinnEnvironment,
        subscriptionKey: String,
        maskinportenClientId: String,
        maskinportenKey: MaskinportenKey,
        httpClient: OutgoingHttpClient,
    ) : this(
        platformBaseUrl = environment.platformBaseUrl,
        maskinportenTokenUrl = environment.maskinportenTokenUrl,
        subscriptionKey = subscriptionKey,
        maskinportenClientId = maskinportenClientId,
        maskinportenKey = maskinportenKey,
        httpClient = httpClient,
    )

    public constructor(
        platformBaseUrl: URI,
        maskinportenTokenUrl: URI,
        subscriptionKey: String,
        maskinportenClientId: String,
        maskinportenKey: MaskinportenKey,
        httpClient: OutgoingHttpClient,
    ) : this(
        platformBaseUrl = platformBaseUrl.toString(),
        tokenProvider = MaskinportenAltinnTokenProvider(
            MaskinportenConfig(maskinportenTokenUrl.toString(), maskinportenClientId, maskinportenKey),
            platformBaseUrl.toString(),
            httpClient,
        ),
        subscriptionKey = subscriptionKey,
        httpClient = httpClient,
    )

    private val authorizeUrl: URI = Http.url(platformBaseUrl, AUTHORIZE_PATH)

    public suspend fun authorize(
        subject: PdpSubject,
        resourceId: ResourceId,
        customerOrganizationNumber: OrganizationNumber,
        action: ActionId,
    ): PdpAuthorization {
        val token = tokenProvider.accessToken()
        val body = Http.json.encodeToString(
            XacmlAuthorizationRequest.serializer(),
            XacmlAuthorizationRequest.of(subject, resourceId, customerOrganizationNumber, action),
        ).encodeToByteArray()
        val request = OutgoingRequest(
            method = HttpMethod.POST,
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

    private fun authorizationOf(response: OutgoingResponse): PdpAuthorization {
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
