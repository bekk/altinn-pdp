package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import no.bekk.bekkopen.person.FodselsnummerValidator
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import java.net.http.HttpClient
import java.time.Duration

internal fun pdpClient(config: AppConfig): PdpClient = PdpClient(
    environment = config.altinnEnvironment,
    subscriptionKey = config.altinnSubscriptionKey,
    maskinportenClientId = config.maskinportenClientId,
    maskinportenKey = config.maskinportenKey,
    httpClient = JavaPdpHttpClient(
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        REQUEST_TIMEOUT,
    ),
)

internal fun configurePidValidation(environment: AltinnEnvironment) {
    FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS = environment == AltinnEnvironment.TT02
}

internal fun ApplicationConfig.decisionCache(): PdpDecisionCache {
    val enabled = boolean("decisionCache.enabled", default = false)
    val timeToLive = Duration.ofSeconds(positiveLong("decisionCache.timeToLiveSeconds", default = 60))
    val maximumSize = positiveLong("decisionCache.maximumSize", default = 10_000)
    return if (enabled) InMemoryPdpDecisionCache(timeToLive, maximumSize) else NoPdpDecisionCache
}

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
