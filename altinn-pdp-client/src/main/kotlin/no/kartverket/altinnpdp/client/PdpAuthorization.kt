package no.kartverket.altinnpdp.client

/** We never see the caller's token, so obligations are passed out rather than enforced here. */
public data class PdpObligation(
    val id: String?,
    val category: String,
    val value: String,
)

public data class PdpAuthorization(
    val decision: PdpDecision,
    val statusCode: String? = null,
    val obligations: List<PdpObligation> = emptyList(),
) {
    val isPermit: Boolean get() = decision.isPermit

    val minimumAuthenticationLevel: Int? get() = levelFor(CATEGORY_MINIMUM_AUTHENTICATION_LEVEL)

    val minimumAuthenticationLevelOrg: Int? get() = levelFor(CATEGORY_MINIMUM_AUTHENTICATION_LEVEL_ORG)

    private fun levelFor(category: String): Int? =
        obligations.firstOrNull { it.category == category }?.value?.toIntOrNull()

    internal companion object {
        const val CATEGORY_MINIMUM_AUTHENTICATION_LEVEL = "urn:altinn:minimum-authenticationlevel"

        const val CATEGORY_MINIMUM_AUTHENTICATION_LEVEL_ORG = "urn:altinn:minimum-authenticationlevel-org"
    }
}
