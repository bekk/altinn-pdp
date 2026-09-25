package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.http.PdpHttpClient

internal const val SAMPLE_SYSTEMUSER_ID = "1725580f-70f4-4ace-a748-4f912497a0d7"

internal fun testPdpClient(
    baseUrl: String,
    tokenProvider: AltinnTokenProvider = FakeTokenProvider(),
    subscriptionKey: String = "subscription-key",
    httpClient: PdpHttpClient = testHttpClient,
) = PdpClient(
    platformBaseUrl = baseUrl,
    tokenProvider = tokenProvider,
    subscriptionKey = subscriptionKey,
    httpClient = httpClient,
)

/** The one request every PDP test makes, so no test has to spell out four valid arguments. */
internal suspend fun PdpClient.authorizeSample() =
    authorize(
        SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
        ResourceId.parse("test-resource"),
        OrganizationNumber.parse("923609016"),
        ActionId.parse("read"),
    )

internal fun pdpDecisionResponse(decision: String = "Permit") = """{"Response":[{"Decision":"$decision"}]}"""

/** Answers with [response], but only after [millis]. A sleep is a floor, so it cannot pass by luck. */
internal fun slowly(millis: Long, response: TestResponse): (RecordedRequest) -> TestResponse = {
    Thread.sleep(millis)
    response
}
