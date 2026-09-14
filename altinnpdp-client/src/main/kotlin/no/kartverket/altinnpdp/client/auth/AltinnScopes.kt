package no.kartverket.altinnpdp.client.auth

/**
 * Maskinporten scopes required to administer delegation setup in Altinn (step 2 in
 * [Altinn-delegering i Maskinporten](https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/delegering)).
 */
object AltinnScopes {
    /** Read resources in the Altinn Resource Registry. */
    const val RESOURCE_READ = "altinn:resourceregistry/resource.read"

    /** Create and modify resources in the Altinn Resource Registry. */
    const val RESOURCE_WRITE = "altinn:resourceregistry/resource.write"

    /** Ask the PDP whether a systembruker has access to a resource. */
    const val AUTHORIZE = "altinn:authorization/authorize"
}
