package no.bekk.altinnpdp.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.bekk.altinnpdp.AltinnEnvironment
import no.bekk.altinnpdp.auth.AltinnScopes
import no.bekk.altinnpdp.auth.AltinnTokenProvider
import no.bekk.altinnpdp.auth.MaskinportenAltinnTokenProvider
import no.bekk.altinnpdp.auth.MaskinportenConfig
import no.bekk.altinnpdp.exception.PdpException
import no.bekk.altinnpdp.http.Http
import no.bekk.altinnpdp.model.XacmlAuthorizationRequest
import no.bekk.altinnpdp.model.XacmlAuthorizationResponse

/**
 * Calls Altinn's PDP (`POST /authorization/api/v1/authorize`) to check whether a systembruker
 * has been delegated access to a resource - the question a valid Maskinporten token alone cannot
 * answer, since it only proves the systembruker belongs to the calling system, not that it was
 * ever granted access to any particular resource.
 *
 * Deliberately knows nothing about any specific API: [authorize]'s `resourceId`,
 * `organizationNumber` and `action` are supplied by the caller on every call, so one client can
 * be reused across different APIs/resources without carrying any single one's configuration.
 *
 * @param platformBaseUrl for example `https://platform.tt02.altinn.no` - use the
 *   [AltinnEnvironment] constructor instead when calling TT02 or prod, so the URL can't be
 *   mistyped
 * @param subscriptionKey the Azure API Management subscription key for the Access Management
 *   products, ordered from Altinn servicedesk - sent as the [SUBSCRIPTION_KEY_HEADER] header,
 *   without which the gateway rejects the call with 401 before the PDP sees it
 */
class PdpClient(
    platformBaseUrl: String,
    private val tokenProvider: AltinnTokenProvider,
    private val subscriptionKey: String,
    private val httpClient: HttpClient = Http.defaultClient(),
) {
    /** Calls [environment] instead of an arbitrary URL - the common case outside of tests. */
    constructor(
        environment: AltinnEnvironment,
        tokenProvider: AltinnTokenProvider,
        subscriptionKey: String,
        httpClient: HttpClient = Http.defaultClient(),
    ) : this(environment.platformBaseUrl, tokenProvider, subscriptionKey, httpClient)

    private val authorizeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + AUTHORIZE_PATH)

    /**
     * @param systemuserId the systembruker id from the token's `authorization_details`
     * @param resourceId the resource's identifier in the Altinn Resource Registry
     * @param organizationNumber the plain Norwegian org number of the party (customer) whose
     *   access is being checked, e.g. `"923609016"` - not the ISO6523-prefixed form Maskinporten
     *   tokens use
     * @param action e.g. `"read"` or `"write"`
     */
    suspend fun authorize(
        systemuserId: String,
        resourceId: String,
        organizationNumber: String,
        action: String,
    ): PdpDecision {
        val subject = required(systemuserId, "systemuserId")
        val resource = required(resourceId, "resourceId")
        val org = required(organizationNumber, "organizationNumber")
        val actionId = required(action, "action")

        val token = tokenProvider.getAltinnToken()
        val body = json.encodeToString(
            XacmlAuthorizationRequest.serializer(),
            XacmlAuthorizationRequest.forSystemUser(subject, resource, org, actionId),
        )
        val request = HttpRequest.newBuilder(authorizeUrl)
            .header("Authorization", "Bearer ${token.value}")
            .header(SUBSCRIPTION_KEY_HEADER, subscriptionKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Http.DEFAULT_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = Http.send(httpClient, request, "Altinn PDP") { message, cause ->
            PdpException(message, cause = cause)
        }
        if (response.statusCode() != 200) {
            throw PdpException(
                "Altinn responded ${response.statusCode()} to the PDP authorization request",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        }
        return decisionOf(response)
    }

    /** Convenience for the common case of only needing a permit/deny boolean. */
    suspend fun isPermitted(
        systemuserId: String,
        resourceId: String,
        organizationNumber: String,
        action: String,
    ): Boolean = authorize(systemuserId, resourceId, organizationNumber, action).isPermit

    private fun decisionOf(response: HttpResponse<String>): PdpDecision {
        val parsed = try {
            json.decodeFromString(XacmlAuthorizationResponse.serializer(), response.body())
        } catch (e: SerializationException) {
            throw PdpException("Failed to parse the PDP response: ${e.message}", cause = e)
        }
        val decision = parsed.response?.firstOrNull()?.decision
            ?: throw PdpException(
                "The PDP response had no Response entries with a decision",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        return try {
            PdpDecision.fromXacmlValue(decision)
        } catch (e: IllegalArgumentException) {
            throw PdpException(
                "Unknown XACML decision \"$decision\" in PDP response",
                statusCode = response.statusCode(),
                responseBody = response.body(),
                cause = e,
            )
        }
    }

    private fun required(value: String, name: String): String {
        require(value.isNotBlank()) { "$name is required" }
        return value
    }

    /**
     * Builds a [PdpClient] from raw config values, so a caller only ever needs to depend on
     * [PdpClient] and [PdpClient.Builder] - not [MaskinportenConfig], [MaskinportenAltinnTokenProvider],
     * or [AltinnTokenProvider] directly. Call [tokenProvider] instead of the `maskinporten*`
     * setters to supply a token source of your own (or a fake, in tests).
     */
    class Builder {
        private var environment: AltinnEnvironment? = null
        private var subscriptionKey: String? = null
        private var httpClient: HttpClient? = null
        private var tokenProvider: AltinnTokenProvider? = null

        private var maskinportenTokenUrl: String? = null
        private var maskinportenClientId: String? = null
        private var maskinportenJwk: String? = null
        private var maskinportenResource: String? = null

        fun environment(environment: AltinnEnvironment): Builder = apply { this.environment = environment }

        fun subscriptionKey(subscriptionKey: String): Builder = apply { this.subscriptionKey = subscriptionKey }

        /** Defaults to a plain [Http.defaultClient]; override to share a client/connection pool. */
        fun httpClient(httpClient: HttpClient): Builder = apply { this.httpClient = httpClient }

        /** Supply your own token source instead of Maskinporten - the `maskinporten*` setters are then ignored. */
        fun tokenProvider(tokenProvider: AltinnTokenProvider): Builder = apply { this.tokenProvider = tokenProvider }

        /** Defaults to [environment]'s own Maskinporten token endpoint; override only for a local test server. */
        fun maskinportenTokenUrl(tokenUrl: String): Builder = apply { this.maskinportenTokenUrl = tokenUrl }

        fun maskinportenClientId(clientId: String): Builder = apply { this.maskinportenClientId = clientId }

        fun maskinportenJwk(jwk: String): Builder = apply { this.maskinportenJwk = jwk }

        /** No per-environment default - Altinn's expected `resource` claim per environment isn't confirmed. */
        fun maskinportenResource(resource: String): Builder = apply { this.maskinportenResource = resource }

        fun build(): PdpClient {
            val env = requireNotNull(environment) { "environment is required" }
            val key = requireNotNull(subscriptionKey) { "subscriptionKey is required" }
            val client = httpClient ?: Http.defaultClient()

            val provider = tokenProvider ?: MaskinportenAltinnTokenProvider(
                maskinportenConfig = MaskinportenConfig(
                    tokenUrl = maskinportenTokenUrl ?: env.maskinportenTokenUrl,
                    clientId = requireNotNull(maskinportenClientId) {
                        "maskinportenClientId is required (or call tokenProvider(...) directly)"
                    },
                    jwk = requireNotNull(maskinportenJwk) {
                        "maskinportenJwk is required (or call tokenProvider(...) directly)"
                    },
                    // Not configurable: PdpClient only ever calls /authorize, and AUTHORIZE is the
                    // one scope that operation needs - not exposed as a builder override.
                    scopes = listOf(AltinnScopes.AUTHORIZE),
                    resource = requireNotNull(maskinportenResource) {
                        "maskinportenResource is required (no per-environment default - not confirmed for prod)"
                    },
                ),
                environment = env,
                httpClient = client,
            )

            return PdpClient(env, provider, key, client)
        }
    }

    companion object {
        const val AUTHORIZE_PATH = "/authorization/api/v1/authorize"

        /** The external `/authorize` endpoint sits behind Azure API Management, which needs this. */
        const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"

        // The PDP response carries fields we don't model (status, obligations, ...); ignore them.
        // Case (Response/response, Decision/decision) is handled per-field via @JsonNames on the
        // response model instead of a blanket case-insensitive mode.
        private val json = Json { ignoreUnknownKeys = true }

        fun builder(): Builder = Builder()
    }
}