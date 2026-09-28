package no.kartverket.altinnpdp.client

public enum class PdpDecision {
    PERMIT,
    DENY,
    NOT_APPLICABLE,
    INDETERMINATE,
    ;

    public val isPermit: Boolean get() = this == PERMIT

    internal companion object {
        fun fromXacmlValue(value: String): PdpDecision = when (value) {
            "Permit" -> PERMIT
            "Deny" -> DENY
            "NotApplicable" -> NOT_APPLICABLE
            "Indeterminate" -> INDETERMINATE
            else -> throw IllegalArgumentException("Unknown XACML decision \"$value\"")
        }
    }
}
