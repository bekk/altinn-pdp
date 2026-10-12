package no.kartverket.altinnpdp.client

import kotlinx.serialization.SerializationException
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import no.kartverket.altinnpdp.client.cache.PdpDecisionCache
import no.kartverket.altinnpdp.client.cache.PdpDecisionKey
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
    private val decisionCache: PdpDecisionCache? = null,
) {
    public constructor(
        environment: AltinnEnvironment,
        subscriptionKey: String,
        maskinportenClientId: String,
        maskinportenKey: MaskinportenKey,
        httpClient: PdpHttpClient,
        decisionCache: PdpDecisionCache? = null,
    ) : this(
        platformBaseUrl = environment.platformBaseUrl,
        tokenProvider = MaskinportenAltinnTokenProvider(
            MaskinportenConfig(environment.maskinportenTokenUrl, maskinportenClientId, maskinportenKey),
            environment,
            httpClient,
        ),
        subscriptionKey = subscriptionKey,
        httpClient = httpClient,
        decisionCache = decisionCache,
    )

    private val authorizeUrl: URI = Http.url(platformBaseUrl, AUTHORIZE_PATH)

    public suspend fun authorize(
        subject: PdpSubject,
        resourceId: ResourceId,
        customerOrganizationNumber: OrganizationNumber,
        action: ActionId,
    ): PdpAuthorization {
        val key = PdpDecisionKey(subject, resourceId, customerOrganizationNumber, action)
        if (decisionCache == null) return requestDecision(key)
        return decisionCache.get(key) { requestDecision(key) }
    }

    private suspend fun requestDecision(key: PdpDecisionKey): PdpAuthorization {
        val token = tokenProvider.getAltinnToken()
        val body = Http.json.encodeToString(
            XacmlAuthorizationRequest.serializer(),
            XacmlAuthorizationRequest.of(key.subject, key.resourceId, key.customerOrganizationNumber, key.action),
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
