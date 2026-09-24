package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import java.time.Clock
import java.time.Duration

class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

    constructor(
        maskinportenConfig: MaskinportenConfig,
        environment: AltinnEnvironment,
        httpClient: PdpHttpClient,
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
}
