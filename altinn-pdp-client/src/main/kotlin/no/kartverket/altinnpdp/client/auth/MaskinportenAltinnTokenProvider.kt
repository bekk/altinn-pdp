package no.kartverket.altinnpdp.client.auth

import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.Timeouts
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    private val timeouts: Timeouts,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

    constructor(
        maskinportenConfig: MaskinportenConfig,
        environment: AltinnEnvironment,
        timeouts: Timeouts,
        httpClient: HttpClient = Http.defaultClient(),
        clock: Clock = Clock.systemUTC(),
        refreshLeeway: Duration = Duration.ofSeconds(30),
    ) : this(
        MaskinportenClient(maskinportenConfig, timeouts, httpClient, clock, refreshLeeway),
        AltinnTokenExchanger(environment, timeouts, httpClient),
        timeouts,
        clock,
        refreshLeeway,
    )

    private val cache = TokenCache<AltinnToken>(clock, refreshLeeway)

    override suspend fun getAltinnToken(): AltinnToken =
        Http.withBudget(
            budget = timeouts.total,
            operation = "Altinn token retrieval",
            exception = { message -> AltinnException(message) },
        ) {
            cache.get { exchanger.exchange(maskinportenClient.getToken()) }
        }
}
