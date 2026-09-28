package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.Serializable
import no.kartverket.altinnpdp.client.PdpDecision

@Serializable
data class AuthorizeResponse(
    val permit: Boolean,
    val decision: PdpDecision,
    val status: String? = null,
    val minimumAuthenticationLevel: Int? = null,
    val minimumAuthenticationLevelOrg: Int? = null,
)
