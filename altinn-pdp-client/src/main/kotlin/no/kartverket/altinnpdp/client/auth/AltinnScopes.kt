package no.kartverket.altinnpdp.client.auth

/** Maskinporten scopes used to authorize requests to Altinn's PDP. */
object AltinnScopes {
    /** Ask the PDP whether a systembruker has access to a resource. */
    const val AUTHORIZE = "altinn:authorization/authorize"
}
