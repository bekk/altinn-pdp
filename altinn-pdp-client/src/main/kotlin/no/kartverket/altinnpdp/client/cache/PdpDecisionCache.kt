package no.kartverket.altinnpdp.client.cache

import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.ResourceId

public interface PdpDecisionCache {
    public suspend fun get(key: PdpDecisionKey, load: suspend () -> PdpAuthorization): PdpAuthorization
}

public data class PdpDecisionKey(
    val subject: PdpSubject,
    val resourceId: ResourceId,
    val customerOrganizationNumber: OrganizationNumber,
    val action: ActionId,
)
