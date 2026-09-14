package no.kartverket.altinnpdp.client

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class PdpDecisionTest {

    @Test
    fun `rejects a decision it does not recognise instead of guessing`() {
        // Defaulting an unrecognised decision to DENY would be safe; defaulting it to anything
        // else would not. Throwing keeps that choice from being made by accident later.
        val e = assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("Maybe") }

        assertContains(e.message!!, "Maybe")
    }

    @Test
    fun `matches the XACML spelling exactly`() {
        // Altinn sends "Permit", not "PERMIT" or "permit". If that ever changes it should break
        // here rather than silently stop permitting anyone.
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("PERMIT") }
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("permit") }
        assertFailsWith<IllegalArgumentException> { PdpDecision.fromXacmlValue("") }
    }
}
