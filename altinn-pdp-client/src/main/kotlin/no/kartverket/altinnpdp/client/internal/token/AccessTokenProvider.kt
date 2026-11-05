package no.kartverket.altinnpdp.client.internal.token

internal fun interface AccessTokenProvider {
    suspend fun accessToken(): AccessToken
}
