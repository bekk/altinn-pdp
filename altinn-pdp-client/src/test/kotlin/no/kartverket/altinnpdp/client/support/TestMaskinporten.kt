package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.http.OutgoingResponse
import no.kartverket.altinnpdp.client.internal.maskinporten.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.internal.maskinporten.MaskinportenClient
import no.kartverket.altinnpdp.client.internal.maskinporten.MaskinportenConfig
import no.kartverket.altinnpdp.client.internal.token.AltinnTokenExchanger
import java.time.Clock
import java.time.Duration

internal const val TOKEN_PATH = "/token"

internal fun maskinportenConfig(
    tokenUrl: String = "https://test.maskinporten.no/token",
    clientId: String = "my-client-id",
) = MaskinportenConfig(tokenUrl, clientId, testMaskinportenKey)

internal fun maskinportenTokenResponse(accessToken: String = "mp-token", expiresIn: Long? = 3600): String =
    if (expiresIn == null) {
        """{"access_token":"$accessToken","token_type":"Bearer"}"""
    } else {
        """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn}"""
    }

internal fun bothTokens(altinnTokenLifetime: Duration = Duration.ofSeconds(300)): (OutgoingRequest) -> OutgoingResponse = { request ->
    when (request.url.path) {
        TOKEN_PATH -> response(200, "application/json", maskinportenTokenResponse())
        AltinnTokenExchanger.EXCHANGE_PATH -> response(200, "text/plain", signedJwt(NOW.plus(altinnTokenLifetime)))
        else -> error("unexpected call to ${request.url}")
    }
}

internal fun maskinportenAltinnTokenProvider(
    httpClient: OutgoingHttpClient,
    clock: Clock = MutableClock(),
) = MaskinportenAltinnTokenProvider(
    maskinportenClient = MaskinportenClient(maskinportenConfig(), httpClient, clock = clock),
    exchanger = AltinnTokenExchanger(PLATFORM_URL, httpClient),
    clock = clock,
)
