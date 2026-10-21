package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient

internal fun testPdpClient(
    baseUrl: String,
    subscriptionKey: String = "subscription-key",
    httpClient: OutgoingHttpClient = testHttpClient,
) = PdpClient(
    platformBaseUrl = baseUrl,
    tokenProvider = FakeTokenProvider(),
    subscriptionKey = subscriptionKey,
    httpClient = httpClient,
)

internal suspend fun PdpClient.authorizeSample() =
    authorize(
        SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
        ResourceId.parse("test-resource"),
        OrganizationNumber.parse("923609016"),
        ActionId.parse("read"),
    )

internal suspend fun PdpClient.authorizeSamplePerson() =
    authorize(
        PersonId.parse(SAMPLE_PID),
        ResourceId.parse("test-resource"),
        OrganizationNumber.parse("923609016"),
        ActionId.parse("read"),
    )

internal fun pdpDecisionResponse(decision: String = "Permit") = """{"response":[{"decision":"$decision"}]}"""

internal fun slowly(millis: Long, response: TestResponse): (RecordedRequest) -> TestResponse = {
    Thread.sleep(millis)
    response
}
