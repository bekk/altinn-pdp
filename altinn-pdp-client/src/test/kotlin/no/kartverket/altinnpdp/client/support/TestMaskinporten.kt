package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.auth.AltinnScopes
import no.kartverket.altinnpdp.client.auth.AltinnTokenExchanger
import no.kartverket.altinnpdp.client.auth.MaskinportenAltinnTokenProvider
import no.kartverket.altinnpdp.client.auth.MaskinportenClient
import no.kartverket.altinnpdp.client.auth.MaskinportenConfig
import no.kartverket.altinnpdp.client.http.Timeouts
import java.time.Clock
import java.time.Duration

internal const val TOKEN_PATH = "/token"

internal fun maskinportenConfig(
    tokenUrl: String = "https://test.maskinporten.no/token",
    clientId: String = "my-client-id",
    jwk: String = TestKeys.rsa.toJSONString(),
    scopes: List<String> = listOf(AltinnScopes.AUTHORIZE),
    assertionLifetime: Duration = Duration.ofSeconds(60),
    audience: String? = null,
): MaskinportenConfig = if (audience == null) {
    // Left out rather than passed through, so the derived audience is what is under test.
    MaskinportenConfig(tokenUrl, clientId, jwk, scopes, assertionLifetime)
} else {
    MaskinportenConfig(tokenUrl, clientId, jwk, scopes, assertionLifetime, audience)
}

internal fun maskinportenTokenResponse(accessToken: String = "mp-token", expiresIn: Long? = 3600): String =
    if (expiresIn == null) {
        """{"access_token":"$accessToken","token_type":"Bearer"}"""
    } else {
        """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn}"""
    }

/** Serves the Maskinporten token and the Altinn exchange that follows it, both immediately. */
internal fun TestHttpServer.serveBothTokens(altinnTokenLifetime: Duration = Duration.ofSeconds(300)) = apply {
    on(TOKEN_PATH) { TestResponse(body = maskinportenTokenResponse()) }
    on(AltinnTokenExchanger.EXCHANGE_PATH) { TestResponse(body = signedJwt(NOW.plus(altinnTokenLifetime))) }
}

internal fun maskinportenAltinnTokenProvider(
    server: TestHttpServer,
    timeouts: Timeouts = Timeouts.DEFAULT,
    clock: Clock = Clock.systemUTC(),
) = MaskinportenAltinnTokenProvider(
    maskinportenClient = MaskinportenClient(
        maskinportenConfig(tokenUrl = server.baseUrl + TOKEN_PATH),
        timeouts,
        clock = clock,
    ),
    exchanger = AltinnTokenExchanger(server.baseUrl, timeouts),
    timeouts = timeouts,
    clock = clock,
)
