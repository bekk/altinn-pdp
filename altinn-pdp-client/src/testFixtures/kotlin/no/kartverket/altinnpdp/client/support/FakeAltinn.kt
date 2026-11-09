package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpStatus
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceAttribute
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.http.OutgoingResponse
import no.kartverket.altinnpdp.client.internal.token.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest.Companion.ATTRIBUTE_ACTION_ID
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest.Companion.ATTRIBUTE_ORGANIZATION_NUMBER
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest.Companion.ATTRIBUTE_PERSON_IDENTIFIER
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest.Companion.ATTRIBUTE_RESOURCE
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationRequest.Companion.ATTRIBUTE_SYSTEMUSER_UUID
import no.kartverket.altinnpdp.client.internal.xacml.XacmlAuthorizationResponse
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake of Maskinporten, Altinn's token exchange and Altinn's PDP, for testing code that uses a [PdpClient] without
 * a network. Pass it as the client's `httpClient`.
 */
public class FakeAltinn(
    private val decision: PdpDecision = PdpDecision.PERMIT,
    private val status: PdpStatus = PdpStatus.Ok,
    private val minimumAuthenticationLevel: Int? = null,
    private val pdpStatus: Int = 200,
    private val maskinportenStatus: Int = 200,
    private val exchangeStatus: Int = 200,
) : OutgoingHttpClient {

    private val received = CopyOnWriteArrayList<SentAuthorization>()

    public val authorizations: List<SentAuthorization> get() = received.toList()

    override suspend fun send(request: OutgoingRequest): OutgoingResponse = when (request.url.path) {
        MASKINPORTEN_TOKEN_PATH -> maskinportenToken()
        AltinnTokenExchanger.EXCHANGE_PATH -> altinnToken()
        PdpClient.AUTHORIZE_PATH -> authorize(request)
        else -> error("FakeAltinn got an unexpected call to ${request.url}")
    }

    private fun maskinportenToken(): OutgoingResponse = when (maskinportenStatus) {
        200 -> response(200, JSON, """{"access_token":"mp-token","token_type":"Bearer","expires_in":3600}""")
        else -> response(maskinportenStatus, JSON, """{"error":"invalid_grant"}""")
    }

    private fun altinnToken(): OutgoingResponse = when (exchangeStatus) {
        200 -> response(200, "text/plain", unsignedJwt(expiresAt = Instant.now().plusSeconds(300)))
        else -> response(exchangeStatus, "text/plain", "token exchange failed")
    }

    private fun authorize(request: OutgoingRequest): OutgoingResponse {
        received += sentAuthorizationOf(request)
        if (pdpStatus != 200) return response(pdpStatus, JSON, """{"title":"PDP failed"}""")
        val result = XacmlAuthorizationResponse.Result(
            decision = decision.xacmlValue,
            status = XacmlAuthorizationResponse.Status(XacmlAuthorizationResponse.StatusCode(status.value)),
            obligations = minimumAuthenticationLevel?.let { level ->
                listOf(
                    XacmlAuthorizationResponse.Obligation(
                        id = "urn:altinn:obligation:authenticationLevel1",
                        attributeAssignment = listOf(
                            XacmlAuthorizationResponse.AttributeAssignment(
                                value = level.toString(),
                                category = PdpAuthorization.CATEGORY_MINIMUM_AUTHENTICATION_LEVEL,
                            ),
                        ),
                    ),
                )
            },
        )
        val body = Http.json.encodeToString(XacmlAuthorizationResponse.serializer(), XacmlAuthorizationResponse(listOf(result)))
        return response(200, JSON, body)
    }

    private fun sentAuthorizationOf(request: OutgoingRequest): SentAuthorization {
        val body = requireNotNull(request.body) { "an authorize without a body" }.decodeToString()
        val xacml = Http.json.decodeFromString(XacmlAuthorizationRequest.serializer(), body).request
        val subject = xacml.accessSubject.single().attribute.single()
        val action = xacml.action.single().attribute.single { it.attributeId == ATTRIBUTE_ACTION_ID }
        val resource = xacml.resource.single().attribute
        val known = setOf(ATTRIBUTE_RESOURCE, ATTRIBUTE_ORGANIZATION_NUMBER)
        return SentAuthorization(
            subject = when (subject.attributeId) {
                ATTRIBUTE_SYSTEMUSER_UUID -> SystemUserId.parse(subject.value)
                ATTRIBUTE_PERSON_IDENTIFIER -> PersonId.parse(subject.value)
                else -> error("FakeAltinn got an unknown subject ${subject.attributeId}")
            },
            resourceId = ResourceId.parse(resource.single { it.attributeId == ATTRIBUTE_RESOURCE }.value),
            customerOrganizationNumber = OrganizationNumber.parse(
                resource.single { it.attributeId == ATTRIBUTE_ORGANIZATION_NUMBER }.value,
            ),
            action = ActionId.parse(action.value),
            resourceAttributes = resource.filter { it.attributeId !in known }.map { ResourceAttribute.parse(it.attributeId, it.value) },
        )
    }

    private companion object {
        const val MASKINPORTEN_TOKEN_PATH = "/token"

        const val JSON = "application/json"

        fun unsignedJwt(expiresAt: Instant): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val claims = encoder.encodeToString("""{"exp":${expiresAt.epochSecond}}""".toByteArray())
            return "$header.$claims."
        }
    }
}

public data class SentAuthorization(
    val subject: PdpSubject,
    val resourceId: ResourceId,
    val customerOrganizationNumber: OrganizationNumber,
    val action: ActionId,
    val resourceAttributes: List<ResourceAttribute>,
)
