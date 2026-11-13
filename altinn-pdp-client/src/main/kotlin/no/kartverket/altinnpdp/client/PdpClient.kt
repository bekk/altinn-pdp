package no.kartverket.altinnpdp.client

import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.internal.http.Http
import no.kartverket.altinnpdp.client.internal.maskinporten.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.internal.maskinporten.MaskinportenConfig
import no.kartverket.altinnpdp.client.internal.token.AccessTokenProvider
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest
import no.kartverket.altinnpdp.client.internal.xacml.authorizationOf
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
        resourceAttributes: List<ResourceAttribute> = emptyList(),
    ): PdpAuthorization {
        val repeatedTypes = resourceAttributes.groupingBy { it.type }.eachCount().filterValues { it > 1 }.keys
        require(repeatedTypes.isEmpty()) { "Expected each resource attribute type only once, but got $repeatedTypes more than once" }
        val token = tokenProvider.accessToken()
        val body = Http.json.encodeToString(
            XacmlAuthorizationRequest.serializer(),
            XacmlAuthorizationRequest.of(subject, resourceId, customerOrganizationNumber, action, resourceAttributes),
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

    internal companion object {
        const val AUTHORIZE_PATH = "/authorization/api/v1/authorize"

        const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }
}
