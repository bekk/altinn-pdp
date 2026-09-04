package no.kartverket.altinn.pdp.auth

/**
 * Maskinporten scopes required to administer delegation setup in Altinn (step 2 in
 * [Altinn-delegering i Maskinporten](https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/delegering)).
 */
object AltinnScopes {
    /** Read resources in the Altinn Resource Registry. */
    const val RESOURCE_READ = "altinn:resourceregistry/resource.read"

    /** Create and modify resources in the Altinn Resource Registry. */
    const val RESOURCE_WRITE = "altinn:resourceregistry/resource.write"

    /** Administer delegation setup (MaskinportenSchema) in Altinn. */
    const val DELEGATIONSCHEMES_WRITE = "altinn:maskinporten/delegationschemes.write"

    /** Ask the PDP whether a systembruker has access to a resource. */
    const val AUTHORIZE = "altinn:authorization/authorize"

    /** Read access lists in the Altinn Resource Registry. */
    const val ACCESSLIST_READ = "altinn:resourceregistry/accesslist.read"

    /** Create and modify access lists in the Altinn Resource Registry. */
    const val ACCESSLIST_WRITE = "altinn:resourceregistry/accesslist.write"

    /**
     * Create, read or update a system in Altinn's System Register. There is no separate read
     * scope for this API - this same scope is required to read a system as to write one.
     */
    const val SYSTEMREGISTER_WRITE = "altinn:authentication/systemregister.write"

    /** Read or list systembruker requests. */
    const val SYSTEMUSER_REQUEST_READ = "altinn:authentication/systemuser.request.read"

    /** Create or delete systembruker requests. */
    const val SYSTEMUSER_REQUEST_WRITE = "altinn:authentication/systemuser.request.write"

    /** Every scope required to create and administer delegatable resources. */
    val DELEGATION: List<String> = listOf(RESOURCE_READ, RESOURCE_WRITE, DELEGATIONSCHEMES_WRITE)
}
