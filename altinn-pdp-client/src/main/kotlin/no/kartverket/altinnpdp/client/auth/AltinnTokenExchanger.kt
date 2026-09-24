package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jwt.JWTParser
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpRequest
import java.net.URI
import java.text.ParseException
import java.time.Instant

/**  Altinn's APIs do not accept Maskinporten tokens, so one has to be traded for an Altinn token. */
class AltinnTokenExchanger(
    platformBaseUrl: String,
    private val httpClient: PdpHttpClient,
) {
    constructor(
        environment: AltinnEnvironment,
        httpClient: PdpHttpClient,
    ) : this(environment.platformBaseUrl, httpClient)

    private val exchangeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + EXCHANGE_PATH)

    suspend fun exchange(maskinportenToken: String): AccessToken {
        val request = PdpHttpRequest("GET", exchangeUrl, mapOf("Authorization" to "Bearer $maskinportenToken"))

        val response = Http.sendExpectingOk(httpClient, request, "Altinn token exchange", ::AltinnException)
        val token = response.body.trim()
        if (token.isEmpty()) {
            throw AltinnException(
                "Altinn returned an empty token",
                statusCode = response.statusCode,
                responseBody = response.body,
            )
        }
        return AccessToken(token, expiresAt(token))
    }

    private fun expiresAt(token: String): Instant = try {
        JWTParser.parse(token).jwtClaimsSet?.expirationTime?.toInstant()
            ?: throw AltinnException("The Altinn token has no exp claim")
    } catch (e: ParseException) {
        throw AltinnException("Failed to parse the Altinn token as a JWT", cause = e)
    }

    companion object {
        const val EXCHANGE_PATH = "/authentication/api/v1/exchange/maskinporten"
    }
}
