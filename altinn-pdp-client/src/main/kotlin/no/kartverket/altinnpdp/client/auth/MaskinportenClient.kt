package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

internal class MaskinportenClient(
    private val config: MaskinportenConfig,
    private val httpClient: PdpHttpClient,
    private val clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = TokenCache.DEFAULT_REFRESH_LEEWAY,
) {
    private val cache = TokenCache<MaskinportenToken>(clock, refreshLeeway)
    private val signingKey = config.key.rsaKey

    suspend fun getToken(): MaskinportenToken = cache.get { fetchToken() }

    fun createClientAssertion(): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(config.clientId)
            .audience(config.audience)
            .claim("scope", AltinnScopes.AUTHORIZE)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))

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

    private suspend fun fetchToken(): MaskinportenToken {
        val body = "grant_type=${urlEncode(GRANT_TYPE)}&assertion=${urlEncode(createClientAssertion())}"

        val request = PdpHttpRequest(
            method = "POST",
            url = URI.create(config.tokenUrl),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            body = body,
        )

        val response = Http.sendExpectingOk(httpClient, request, "Maskinporten", ::MaskinportenException)
        return parseTokenResponse(response.body)
    }

    private fun parseTokenResponse(body: String): MaskinportenToken {
        val parsed = try {
            Http.json.decodeFromString(MaskinportenTokenResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw MaskinportenException(
                "Failed to parse the response from Maskinporten as JSON: ${e.message}",
                cause = e,
            )
        }
        if (parsed.accessToken.isBlank()) {
            throw MaskinportenException("The response from Maskinporten had no access_token")
        }
        val lifetimeSeconds = parsed.expiresIn
            ?: throw MaskinportenException("The response from Maskinporten had no expires_in")
        return MaskinportenToken(parsed.accessToken, clock.instant().plusSeconds(lifetimeSeconds))
    }

    companion object {
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"

        private val ASSERTION_LIFETIME: Duration = Duration.ofSeconds(60)

        private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

@Serializable
private data class MaskinportenTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long? = null,
)
