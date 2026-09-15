package no.kartverket.altinnpdp.client.auth

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.http.Http

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
        AltinnTokenExchanger(environment, httpClient),
        clock,
        refreshLeeway,
    )

    private val cache = TokenCache(clock, refreshLeeway)

    override suspend fun getAltinnToken(): AccessToken =
        cache.get { exchanger.exchange(maskinportenClient.getToken().value) }

    suspend fun getMaskinportenToken(): AccessToken = maskinportenClient.getToken()

    suspend fun invalidate() {
        cache.invalidate()
        maskinportenClient.invalidate()
    }
}
