package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.http.Http

/**
 * Fetches an access token from Maskinporten using the JWT grant
 * (`urn:ietf:params:oauth:grant-type:jwt-bearer`).
 *
 * The token is cached and only refetched as it approaches expiry. Safe to call concurrently from
 * multiple coroutines and meant to be reused.
 */
class MaskinportenClient(
    private val config: MaskinportenConfig,
    private val httpClient: HttpClient = Http.defaultClient(),
    private val clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) {
    private val cache = TokenCache(clock, refreshLeeway)
    private val signingKey: RSAKey = parseSigningKey(config.jwk)

    /** A valid access token from Maskinporten, served from cache when possible. */
    suspend fun getToken(): AccessToken = cache.get { fetchToken() }

    /** Clears the cache so the next call fetches a new token. */
    suspend fun invalidate() = cache.invalidate()

    /**
     * Builds and signs the client assertion (the grant JWT) sent to Maskinporten. Exposed for
     * troubleshooting - call [getToken] for normal use.
     */
    fun createClientAssertion(): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(config.clientId)
            .audience(config.audience)
            .claim("scope", config.scopeString)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(config.assertionLifetime)))

        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(signingKey.keyID)
            .build()

        val jwt = SignedJWT(header, claims.build())
        try {
            jwt.sign(RSASSASigner(signingKey))
        } catch (e: JOSEException) {
            throw MaskinportenException("Failed to sign the client assertion: ${e.message}", cause = e)
        }
        return jwt.serialize()
    }

    private suspend fun fetchToken(): AccessToken {
        val body = "grant_type=${urlEncode(GRANT_TYPE)}&assertion=${urlEncode(createClientAssertion())}"

        val request = HttpRequest.newBuilder(URI.create(config.tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .timeout(Http.DEFAULT_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = Http.send(httpClient, request, "Maskinporten") { message, cause ->
            MaskinportenException(message, cause = cause)
        }
        if (response.statusCode() != 200) {
            throw MaskinportenException(
                "Maskinporten responded ${response.statusCode()} to the token request",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        }
        return parseTokenResponse(response.body())
    }

    private fun parseTokenResponse(body: String): AccessToken {
        val parsed = try {
            json.decodeFromString(MaskinportenTokenResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw MaskinportenException(
                "Failed to parse the response from Maskinporten as JSON: ${e.message}",
                cause = e,
            )
        }
        if (parsed.accessToken.isBlank()) {
            throw MaskinportenException("The response from Maskinporten had no access_token")
        }
        val lifetimeSeconds = parsed.expiresIn ?: DEFAULT_LIFETIME_SECONDS
        return AccessToken(parsed.accessToken, clock.instant().plusSeconds(lifetimeSeconds))
    }

    companion object {
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"

        /** Used when Maskinporten does not supply `expires_in`. */
        private const val DEFAULT_LIFETIME_SECONDS = 60L

        // The PDP response carries fields we don't model; ignore them rather than fail.
        private val json = Json { ignoreUnknownKeys = true }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun parseSigningKey(jwk: String): RSAKey {
            val parsed = try {
                JWK.parse(jwk)
            } catch (e: ParseException) {
                throw MaskinportenException("Failed to parse the JWK: ${e.message}", cause = e)
            }
            if (parsed !is RSAKey) {
                throw MaskinportenException(
                    "Maskinporten requires an RSA key, but the JWK is of type ${parsed.keyType}",
                )
            }
            if (!parsed.isPrivate) {
                throw MaskinportenException("The JWK has no private key material and cannot sign")
            }
            return parsed
        }
    }
}

@Serializable
private data class MaskinportenTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long? = null,
)
