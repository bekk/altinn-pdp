package no.kartverket.altinn.pdp.client

/** The XACML `Decision` a PDP call resolves to. */
enum class PdpDecision {
    PERMIT,
    DENY,
    NOT_APPLICABLE,
    INDETERMINATE,
    ;

    val isPermit: Boolean get() = this == PERMIT

    companion object {
        fun fromXacmlValue(value: String): PdpDecision = when (value) {
            "Permit" -> PERMIT
            "Deny" -> DENY
            "NotApplicable" -> NOT_APPLICABLE
            "Indeterminate" -> INDETERMINATE
            else -> throw IllegalArgumentException("Unknown XACML decision \"$value\"")
        }
    }
}
