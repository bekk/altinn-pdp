package no.kartverket.altinnpdp.client.auth

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.Timeouts

/**
 * The real [AltinnTokenProvider]: fetches a token from Maskinporten and exchanges it for an
 * Altinn token, caching both separately and refetching only as they approach expiry.
 *
 * ```
 * val provider = MaskinportenAltinnTokenProvider(
 *     maskinportenConfig = MaskinportenConfig(
 *         tokenUrl = "https://test.maskinporten.no/token",
 *         clientId = "<client id>",
 *         jwk = jwkJson,
 *         scopes = listOf(AltinnScopes.AUTHORIZE),
 *     ),
 *     environment = AltinnEnvironment.TT02,
 * )
 *
 * val altinnToken = provider.getAltinnToken().value
 * ```
 *
 * Safe to call concurrently from multiple coroutines and meant to be reused.
 */
class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    private val timeouts: Timeouts = Timeouts.DEFAULT,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

    /** Builds the Maskinporten client and token exchanger from their configuration directly. */
    constructor(
        maskinportenConfig: MaskinportenConfig,
        environment: AltinnEnvironment,
        timeouts: Timeouts = Timeouts.DEFAULT,
        httpClient: HttpClient = Http.defaultClient(timeouts),
        clock: Clock = Clock.systemUTC(),
        refreshLeeway: Duration = Duration.ofSeconds(30),
    ) : this(
        MaskinportenClient(maskinportenConfig, timeouts, httpClient, clock, refreshLeeway),
        AltinnTokenExchanger(environment, timeouts, httpClient),
        timeouts,
        clock,
        refreshLeeway,
    )

    private val cache = TokenCache(clock, refreshLeeway)

    /**
     * A valid Altinn token, served from cache when possible. Send [AccessToken.value] as
     * `Authorization: Bearer <value>` to the Altinn APIs.
     *
     * A cache miss makes two calls, so the pair runs under [Timeouts.total]. Nested inside
     * `PdpClient.authorize` this budget starts later than that one and so never wins; it is what
     * bounds the provider used on its own.
     */
    override suspend fun getAltinnToken(): AccessToken =
        Http.withBudget(
            budget = timeouts.total,
            operation = "Altinn token retrieval",
            exception = { message -> AltinnException(message) },
        ) {
            cache.get { exchanger.exchange(maskinportenClient.getToken().value) }
        }

    /**
     * The Maskinporten token being exchanged - useful for troubleshooting, and for APIs that
     * accept a Maskinporten token directly.
     */
    suspend fun getMaskinportenToken(): AccessToken = maskinportenClient.getToken()

    /** Clears the cache for both the Altinn and the Maskinporten token, for example after a 401. */
    suspend fun invalidate() {
        cache.invalidate()
        maskinportenClient.invalidate()
    }
}
