package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jwt.JWTParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.text.ParseException
import java.time.Clock
import java.time.Instant
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http

/**
 * Exchanges a Maskinporten token for an Altinn token.
 *
 * Altinn does not accept Maskinporten tokens directly. The exchange happens through
 * `GET /authentication/api/v1/exchange/maskinporten`, with the Maskinporten token sent as a
 * bearer token. The response body is the Altinn token itself (a JWT).
 */
class AltinnTokenExchanger(
    platformBaseUrl: String,
    private val httpClient: HttpClient = Http.defaultClient(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Calls [environment] instead of an arbitrary URL - the common case outside of tests. */
    constructor(
        environment: AltinnEnvironment,
        httpClient: HttpClient = Http.defaultClient(),
        clock: Clock = Clock.systemUTC(),
    ) : this(environment.platformBaseUrl, httpClient, clock)

    private val exchangeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + EXCHANGE_PATH)

    /**
     * Exchanges a Maskinporten token and returns the Altinn token, with the expiry read from the
     * token's own `exp` claim.
     */
    suspend fun exchange(maskinportenToken: String): AccessToken {
        val request = HttpRequest.newBuilder(exchangeUrl)
            .header("Authorization", "Bearer $maskinportenToken")
            .header("Accept", "application/json")
            .timeout(Http.DEFAULT_TIMEOUT)
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
        val token = cleanToken(response.body())
        if (token.isEmpty()) {
            throw AltinnException(
                "Altinn returned an empty token",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        }
        return AccessToken(token, expiresAt(token))
    }

    private fun expiresAt(token: String): Instant {
        val exp = try {
            JWTParser.parse(token).jwtClaimsSet.expirationTime
        } catch (e: ParseException) {
            throw AltinnException("Failed to parse the Altinn token as a JWT", cause = e)
        }
        return exp?.toInstant() ?: clock.instant().plusSeconds(FALLBACK_LIFETIME_SECONDS)
    }

    companion object {
        const val EXCHANGE_PATH = "/authentication/api/v1/exchange/maskinporten"

        /** Used when the Altinn token has no `exp` claim. */
        private const val FALLBACK_LIFETIME_SECONDS = 60L

        /** Altinn returns the JWT as text, in some environments wrapped in quotes. */
        private fun cleanToken(body: String?): String {
            var token = body?.trim() ?: ""
            if (token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length - 1)
            }
            return token.trim()
        }
    }
}
