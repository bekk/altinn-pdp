package no.kartverket.altinnpdp.client

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class PdpDecisionTest {

    @Test
    fun `rejects a decision it does not recognise instead of guessing`() {
        val e = assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("Maybe") }

        assertContains(e.message!!, "Maybe")
    }

    @Test
    fun `matches the XACML spelling exactly`() {
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("PERMIT") }
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("permit") }
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("") }
    }
}
