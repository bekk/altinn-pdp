package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jwt.JWTParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.text.ParseException
import java.time.Instant
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.Timeouts

/**
 * Exchanges a Maskinporten token for an Altinn token.
 *
 * Altinn does not accept Maskinporten tokens directly. The exchange happens through
 * `GET /authentication/api/v1/exchange/maskinporten`, with the Maskinporten token sent as a
 * bearer token. The response body is the Altinn token itself (a JWT).
 */
class AltinnTokenExchanger(
    platformBaseUrl: String,
    private val timeouts: Timeouts = Timeouts.DEFAULT,
    private val httpClient: HttpClient = Http.defaultClient(timeouts),
) {
    /** Calls [environment] instead of an arbitrary URL - the common case outside of tests. */
    constructor(
        environment: AltinnEnvironment,
        timeouts: Timeouts = Timeouts.DEFAULT,
        httpClient: HttpClient = Http.defaultClient(timeouts),
    ) : this(environment.platformBaseUrl, timeouts, httpClient)

    private val exchangeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + EXCHANGE_PATH)

    /**
     * Exchanges a Maskinporten token and returns the Altinn token, with the expiry read from the
     * token's own `exp` claim.
     */
    suspend fun exchange(maskinportenToken: String): AccessToken {
        val request = HttpRequest.newBuilder(exchangeUrl)
            .header("Authorization", "Bearer $maskinportenToken")
            .timeout(timeouts.request)
            .GET()
            .build()

        val response = Http.send(httpClient, request, "Altinn token exchange") { message, cause ->
            AltinnException(message, cause = cause)
        }
        if (response.statusCode() != 200) {
            throw AltinnException(
                "Altinn responded ${response.statusCode()} to the token exchange",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        }
        val token = response.body().trim()
        if (token.isEmpty()) {
            throw AltinnException(
                "Altinn returned an empty token",
                statusCode = response.statusCode(),
                responseBody = response.body(),
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
