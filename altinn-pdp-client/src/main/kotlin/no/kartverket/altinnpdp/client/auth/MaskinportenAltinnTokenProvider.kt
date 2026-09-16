package no.kartverket.altinnpdp.client.auth

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.http.Http
import no.kartverket.altinnpdp.client.http.Timeouts

class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    private val timeouts: Timeouts = Timeouts.DEFAULT,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = Duration.ofSeconds(30),
) : AltinnTokenProvider {

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

    override suspend fun getAltinnToken(): AccessToken =
        Http.withBudget(
            budget = timeouts.total,
            operation = "Altinn token retrieval",
            exception = { message -> AltinnException(message) },
        ) {
            cache.get { exchanger.exchange(maskinportenClient.getToken().value) }
        }

    suspend fun getMaskinportenToken(): AccessToken = maskinportenClient.getToken()

    suspend fun invalidate() {
        cache.invalidate()
        maskinportenClient.invalidate()
    }
}
