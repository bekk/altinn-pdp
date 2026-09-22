package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable

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
