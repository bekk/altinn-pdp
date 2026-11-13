package no.kartverket.altinnpdp.client.internal.maskinporten

import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.internal.token.AccessToken
import no.kartverket.altinnpdp.client.internal.token.AccessTokenProvider
import no.kartverket.altinnpdp.client.internal.token.AltinnToken
import no.kartverket.altinnpdp.client.internal.token.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.internal.token.TokenCache
import java.time.Clock
import java.time.Duration

internal class MaskinportenAltinnTokenProvider(
    private val maskinportenClient: MaskinportenClient,
    private val exchanger: AltinnTokenExchanger,
    clock: Clock = Clock.systemUTC(),
    refreshLeeway: Duration = TokenCache.DEFAULT_REFRESH_LEEWAY,
) : AccessTokenProvider {

    constructor(
        maskinportenConfig: MaskinportenConfig,
        platformBaseUrl: String,
        httpClient: OutgoingHttpClient,
        clock: Clock = Clock.systemUTC(),
        refreshLeeway: Duration = TokenCache.DEFAULT_REFRESH_LEEWAY,
    ) : this(
        MaskinportenClient(maskinportenConfig, httpClient, clock, refreshLeeway),
        AltinnTokenExchanger(platformBaseUrl, httpClient),
        clock,
        refreshLeeway,
    )

    private val cache = TokenCache<AltinnToken>(clock, refreshLeeway)

    override suspend fun accessToken(): AccessToken = AccessToken.of(
        cache.get { exchanger.exchange(maskinportenClient.getToken()) }.value,
    )
}
