package no.kartverket.altinnpdp.client.auth

import java.net.URI

internal data class MaskinportenConfig(
    val tokenUrl: String,
    val clientId: String,
    val key: MaskinportenKey,
) {
    /** Maskinporten requires `aud` to be the token URL without its path. */
    val audience: String = URI.create(tokenUrl).let { "${it.scheme}://${it.authority}/" }
}
