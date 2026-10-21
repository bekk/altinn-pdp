package no.kartverket.altinnpdp.client.auth

internal fun interface AccessTokenProvider {
    suspend fun accessToken(): AccessToken
}
