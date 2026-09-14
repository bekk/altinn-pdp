package no.kartverket.altinnpdp.client.auth

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.http.Http

/**
 * Fetches a token from Maskinporten and exchanges it for an Altinn token, caching the Maskinporten
 * token in [MaskinportenClient] and the Altinn token here, refetching each only as it approaches
 * expiry. Safe to call concurrently from multiple coroutines and meant to be reused.
 */
class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

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

    /** Send [AccessToken.value] as `Authorization: Bearer <value>` to the Altinn APIs. */
    override suspend fun getAltinnToken(): AccessToken =
        cache.get { exchanger.exchange(maskinportenClient.getToken().value) }

    /** Exposed separately for troubleshooting, and for APIs that accept a Maskinporten token directly. */
    suspend fun getMaskinportenToken(): AccessToken = maskinportenClient.getToken()

    /** e.g. after a 401. */
    suspend fun invalidate() {
        cache.invalidate()
        maskinportenClient.invalidate()
    }
}
