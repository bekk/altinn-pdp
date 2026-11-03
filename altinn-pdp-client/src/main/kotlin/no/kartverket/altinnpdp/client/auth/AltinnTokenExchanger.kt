package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jwt.JWTParser
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.HttpMethod
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import java.net.URI
import java.text.ParseException
import java.time.Instant

/**  Altinn's APIs do not accept Maskinporten tokens, so one has to be traded for an Altinn token. */
internal class AltinnTokenExchanger(
    platformBaseUrl: String,
    private val httpClient: OutgoingHttpClient,
) {
    private val exchangeUrl: URI = Http.url(platformBaseUrl, EXCHANGE_PATH)

    suspend fun exchange(maskinportenToken: MaskinportenToken): AltinnToken {
        val request = OutgoingRequest(HttpMethod.GET, exchangeUrl, mapOf("Authorization" to "Bearer ${maskinportenToken.value}"))

        val response = Http.sendExpectingOk(httpClient, request, "Altinn token exchange", ::AltinnException)
        val body = response.body.decodeToString()
        val token = body.trim()
        if (token.isEmpty()) {
            throw AltinnException(
                "Altinn returned an empty token",
                statusCode = response.statusCode,
                responseBody = body,
            )
        }
        return AltinnToken(token, expiresAt(token))
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
