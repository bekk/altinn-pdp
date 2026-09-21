package no.kartverket.altinnpdp.restserver

import kotlinx.serialization.Serializable

@Serializable
data class AuthorizeRequest(
    val systemuserId: String,
    val resourceId: String,
    // The party (customer) whose access is being checked, not the calling system's own org number.
    val organizationNumber: String,
    val action: String,
) {
    fun requireValidOrganizationNumber() {
        require(organizationNumber.matches(ORG_NUMBER_REGEX)) {
            "organizationNumber must be exactly 9 digits"
        }
    }
}

private val ORG_NUMBER_REGEX = Regex("""\d{9}""")

// `decision` carries the raw XACML name alongside `permit` so a caller can tell an explicit DENY
// from NOT_APPLICABLE - a distinction `permit` collapses into the same `false`.
@Serializable
data class AuthorizeResponse(
    val permit: Boolean,
    val decision: String,
    val status: String? = null,
    val minimumAuthenticationLevel: Int? = null,
    val minimumAuthenticationLevelOrg: Int? = null,
)

@Serializable
data class ErrorResponse(val error: String)
