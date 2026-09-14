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

/** Altinn does not accept Maskinporten tokens directly. */
class AltinnTokenExchanger(
    platformBaseUrl: String,
    private val httpClient: HttpClient = Http.defaultClient(),
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        environment: AltinnEnvironment,
        httpClient: HttpClient = Http.defaultClient(),
        clock: Clock = Clock.systemUTC(),
    ) : this(environment.platformBaseUrl, httpClient, clock)

    private val exchangeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + EXCHANGE_PATH)

    suspend fun exchange(maskinportenToken: String): AccessToken {
        val request = HttpRequest.newBuilder(exchangeUrl)
            .header("Authorization", "Bearer $maskinportenToken")
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

        private const val FALLBACK_LIFETIME_SECONDS = 60L
    }
}
