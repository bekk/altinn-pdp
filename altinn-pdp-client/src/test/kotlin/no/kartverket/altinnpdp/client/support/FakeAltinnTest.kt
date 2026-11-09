package no.kartverket.altinnpdp.client.support

import kotlinx.coroutines.runBlocking
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.AltinnException
import no.kartverket.altinnpdp.client.MaskinportenException
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpException
import no.kartverket.altinnpdp.client.PdpStatus
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceAttribute
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FakeAltinnTest {

    private fun client(altinn: FakeAltinn) = PdpClient(
        environment = AltinnEnvironment.TT02,
        subscriptionKey = "subscription-key",
        maskinportenClientId = "client-id",
        maskinportenKey = testMaskinportenKey,
        httpClient = altinn,
    )

    @Test
    fun `answers with the decision it was given`() = runBlocking {
        for (decision in PdpDecision.entries) {
            val authorization = client(FakeAltinn(decision = decision)).authorizeSample()

            assertEquals(decision, authorization.decision)
            assertEquals(PdpStatus.Ok, authorization.status)
            assertNull(authorization.minimumAuthenticationLevel)
        }
    }

    @Test
    fun `answers with the status it was given`() = runBlocking {
        val status = PdpStatus.Other("urn:oasis:names:tc:xacml:1.0:status:missing-attribute")

        assertEquals(status, client(FakeAltinn(status = status)).authorizeSample().status)
    }

    @Test
    fun `answers with a minimum authentication level when given one`() = runBlocking {
        assertEquals(3, client(FakeAltinn(minimumAuthenticationLevel = 3)).authorizeSample().minimumAuthenticationLevel)
    }

    @Test
    fun `records what the client asked, in the client's own terms`() = runBlocking {
        val altinn = FakeAltinn()
        val attributes = listOf(
            ResourceAttribute.parse("urn:altinn:subresource", "metadata"),
            ResourceAttribute.parse("urn:kv:geonorge", "service"),
        )
        client(altinn).authorizeSample()
        client(altinn).authorize(
            PersonId.parse(SAMPLE_PID),
            ResourceId.parse("other-resource"),
            OrganizationNumber.parse("923609016"),
            ActionId.parse("write"),
            attributes,
        )

        assertEquals(
            listOf(
                SentAuthorization(
                    SystemUserId.parse(SAMPLE_SYSTEMUSER_ID),
                    ResourceId.parse("test-resource"),
                    OrganizationNumber.parse("923609016"),
                    ActionId.parse("read"),
                    emptyList(),
                ),
                SentAuthorization(
                    PersonId.parse(SAMPLE_PID),
                    ResourceId.parse("other-resource"),
                    OrganizationNumber.parse("923609016"),
                    ActionId.parse("write"),
                    attributes,
                ),
            ),
            altinn.authorizations,
        )
    }

    @Test
    fun `fails each step with the status it was given`() = runBlocking {
        val pdp = assertFailsWith<PdpException> { client(FakeAltinn(pdpStatus = 400)).authorizeSample() }
        assertEquals(400, pdp.statusCode)

        val maskinporten = assertFailsWith<MaskinportenException> { client(FakeAltinn(maskinportenStatus = 400)).authorizeSample() }
        assertEquals(400, maskinporten.statusCode)

        val exchange = assertFailsWith<AltinnException> { client(FakeAltinn(exchangeStatus = 400)).authorizeSample() }
        assertEquals(400, exchange.statusCode)
    }
}
