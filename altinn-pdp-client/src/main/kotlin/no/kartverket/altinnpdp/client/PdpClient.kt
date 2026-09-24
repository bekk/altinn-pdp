package no.kartverket.altinnpdp.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.auth.AltinnScopes
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.exception.PdpException
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.Timeouts
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationRequest
import no.kartverket.altinnpdp.client.model.XacmlAuthorizationResponse
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PdpClient(
    platformBaseUrl: String,
    private val tokenProvider: AltinnTokenProvider,
    private val subscriptionKey: String,
    private val timeouts: Timeouts,
    private val httpClient: HttpClient = Http.defaultClient(),
) {
    constructor(
        environment: AltinnEnvironment,
        tokenProvider: AltinnTokenProvider,
        subscriptionKey: String,
        timeouts: Timeouts,
        httpClient: HttpClient = Http.defaultClient(),
    ) : this(environment.platformBaseUrl, tokenProvider, subscriptionKey, timeouts, httpClient)

    private val authorizeUrl: URI = URI.create(Http.withoutTrailingSlash(platformBaseUrl) + AUTHORIZE_PATH)

    suspend fun authorize(
        systemuserId: String,
        resourceId: String,
        customerOrganizationNumber: String,
        action: String,
    ): PdpAuthorization {
        val errors = PdpRequestValidation.validate(systemuserId, resourceId, customerOrganizationNumber, action)
        if (errors.isNotEmpty()) throw PdpValidationException(errors)

        return Http.withBudget(
            budget = timeouts.total,
            operation = "The PDP authorization lookup",
            exception = { message -> PdpException(message) },
        ) {
            fetchAuthorization(systemuserId, resourceId, customerOrganizationNumber, action)
        }
    }

    private suspend fun fetchAuthorization(
        subject: String,
        resource: String,
        org: String,
        actionId: String,
    ): PdpAuthorization {
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
            .timeout(timeouts.request)
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
        return authorizationOf(response)
    }

    suspend fun isPermitted(
        systemuserId: String,
        resourceId: String,
        customerOrganizationNumber: String,
        action: String,
    ): Boolean = authorize(systemuserId, resourceId, customerOrganizationNumber, action).isPermit

    private fun authorizationOf(response: HttpResponse<String>): PdpAuthorization {
        val parsed = try {
            json.decodeFromString(XacmlAuthorizationResponse.serializer(), response.body())
        } catch (e: SerializationException) {
            throw PdpException("Failed to parse the PDP response: ${e.message}", cause = e)
        }
        val results = parsed.response.orEmpty()
        if (results.size > 1) {
            throw PdpException(
                "The PDP response had ${results.size} Response entries, but only one decision was requested",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        }
        val result = results.firstOrNull()
        val decision = result?.decision
            ?: throw PdpException(
                "The PDP response had no Response entries with a decision",
                statusCode = response.statusCode(),
                responseBody = response.body(),
            )
        val parsedDecision = try {
            PdpDecision.fromXacmlValue(decision)
        } catch (e: IllegalArgumentException) {
            throw PdpException(
                "Unknown XACML decision \"$decision\" in PDP response",
                statusCode = response.statusCode(),
                responseBody = response.body(),
                cause = e,
            )
        }
        return PdpAuthorization(
            decision = parsedDecision,
            statusCode = result.status?.statusCode?.value,
            obligations = obligationsOf(result),
        )
    }

    private fun obligationsOf(result: XacmlAuthorizationResponse.Result): List<PdpObligation> =
        result.obligations.orEmpty().flatMap { obligation ->
            obligation.attributeAssignment.orEmpty().mapNotNull { assignment ->
                val category = assignment.category ?: return@mapNotNull null
                val value = assignment.value ?: return@mapNotNull null
                PdpObligation(id = obligation.id, category = category, value = value)
            }
        }

    class Builder {
        private var environment: AltinnEnvironment? = null
        private var subscriptionKey: String? = null
        private var httpClient: HttpClient? = null
        private var tokenProvider: AltinnTokenProvider? = null
        private var timeouts: Timeouts? = null

        private var maskinportenTokenUrl: String? = null
        private var maskinportenClientId: String? = null
        private var maskinportenJwk: String? = null

        fun environment(environment: AltinnEnvironment): Builder = apply { this.environment = environment }

        fun subscriptionKey(subscriptionKey: String): Builder = apply { this.subscriptionKey = subscriptionKey }

        fun httpClient(httpClient: HttpClient): Builder = apply { this.httpClient = httpClient }

        fun timeouts(timeouts: Timeouts): Builder = apply { this.timeouts = timeouts }

        fun tokenProvider(tokenProvider: AltinnTokenProvider): Builder = apply { this.tokenProvider = tokenProvider }

        fun maskinportenTokenUrl(tokenUrl: String): Builder = apply { this.maskinportenTokenUrl = tokenUrl }

        fun maskinportenClientId(clientId: String): Builder = apply { this.maskinportenClientId = clientId }

        fun maskinportenJwk(jwk: String): Builder = apply { this.maskinportenJwk = jwk }

        fun build(): PdpClient {
            val env = requireNotNull(environment) { "environment is required" }
            val key = requireNotNull(subscriptionKey) { "subscriptionKey is required" }
            val timeouts = requireNotNull(timeouts) {
                "timeouts is required - size them to fit inside your own callers' budget, or pass " +
                    "Timeouts.DEFAULT to take the reference values deliberately"
            }
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
                    // Not a builder setting: this client only ever calls /authorize, and that is
                    // the one scope the endpoint needs.
                    scopes = listOf(AltinnScopes.AUTHORIZE),
                ),
                environment = env,
                timeouts = timeouts,
                httpClient = client,
            )

            return PdpClient(env, provider, key, timeouts, client)
        }
    }

    companion object {
        const val AUTHORIZE_PATH = "/authorization/api/v1/authorize"

        const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"

        private val json = Json { ignoreUnknownKeys = true }

        fun builder(): Builder = Builder()
    }
}
