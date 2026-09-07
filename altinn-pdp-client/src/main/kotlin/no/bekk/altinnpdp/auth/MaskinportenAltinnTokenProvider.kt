package no.bekk.altinnpdp.auth

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import no.bekk.altinnpdp.AltinnEnvironment
import no.bekk.altinnpdp.http.Http

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
 *         scopes = AltinnScopes.DELEGATION,
 *         resource = "https://tt02.altinn.no",
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
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

    /** Builds the Maskinporten client and token exchanger from their configuration directly. */
    constructor(
        maskinportenConfig: MaskinportenConfig,
        environment: AltinnEnvironment,
        httpClient: HttpClient = Http.defaultClient(),
        clock: Clock = Clock.systemUTC(),
        refreshLeeway: Duration = Duration.ofSeconds(30),
    ) : this(
        MaskinportenClient(maskinportenConfig, httpClient, clock, refreshLeeway),
        AltinnTokenExchanger(environment, httpClient, clock),
        clock,
        refreshLeeway,
    )

    private val cache = TokenCache(clock, refreshLeeway)

    /**
     * A valid Altinn token, served from cache when possible. Send [AccessToken.value] as
     * `Authorization: Bearer <value>` to the Altinn APIs.
     */
    override suspend fun getAltinnToken(): AccessToken =
        cache.get { exchanger.exchange(maskinportenClient.getToken().value) }

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
