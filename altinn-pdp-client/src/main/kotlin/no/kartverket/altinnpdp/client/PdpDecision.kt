package no.kartverket.altinnpdp.client

public enum class PdpDecision(internal val xacmlValue: String) {
    PERMIT("Permit"),
    DENY("Deny"),
    NOT_APPLICABLE("NotApplicable"),
    INDETERMINATE("Indeterminate"),
    ;

    public val isPermit: Boolean get() = this == PERMIT

    internal companion object {
        fun fromXacmlValue(value: String): PdpDecision? = entries.find { it.xacmlValue == value }
    }
}
