package no.kartverket.altinnpdp.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdpAuthorizationTest {

    private fun obligation(category: String, value: String) =
        PdpObligation(id = "urn:altinn:obligation:authenticationLevel1", category = category, value = value)

    @Test
    fun `reads both authentication levels out of the obligations`() {
        val authorization = PdpAuthorization(
            decision = PdpDecision.PERMIT,
            obligations = listOf(
                obligation(PdpAuthorization.CATEGORY_MINIMUM_AUTHENTICATION_LEVEL, "3"),
                obligation(PdpAuthorization.CATEGORY_MINIMUM_AUTHENTICATION_LEVEL_ORG, "4"),
            ),
        )

        assertEquals(3, authorization.minimumAuthenticationLevel)
        assertEquals(4, authorization.minimumAuthenticationLevelOrg)
        assertTrue(authorization.isPermit)
    }

    @Test
    fun `a decision without obligations has no authentication levels`() {
        val authorization = PdpAuthorization(decision = PdpDecision.NOT_APPLICABLE)

        assertNull(authorization.minimumAuthenticationLevel)
        assertNull(authorization.minimumAuthenticationLevelOrg)
        assertTrue(authorization.obligations.isEmpty())
    }

    @Test
    fun `a non-numeric obligation value is reported as absent rather than crashing`() {
        val authorization = PdpAuthorization(
            decision = PdpDecision.PERMIT,
            obligations = listOf(obligation(PdpAuthorization.CATEGORY_MINIMUM_AUTHENTICATION_LEVEL, "high")),
        )

        assertNull(authorization.minimumAuthenticationLevel)
        assertEquals(1, authorization.obligations.size)
    }

    @Test
    fun `an obligation category the client does not know is still carried`() {
        val authorization = PdpAuthorization(
            decision = PdpDecision.PERMIT,
            obligations = listOf(obligation("urn:altinn:some-future-obligation", "1")),
        )

        assertNull(authorization.minimumAuthenticationLevel)
        assertEquals("urn:altinn:some-future-obligation", authorization.obligations.single().category)
    }
}
