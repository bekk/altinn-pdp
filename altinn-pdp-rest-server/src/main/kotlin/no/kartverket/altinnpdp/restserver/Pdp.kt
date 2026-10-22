package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import no.bekk.bekkopen.person.FodselsnummerValidator
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.JavaOutgoingHttpClient
import java.net.http.HttpClient
import java.time.Duration

internal fun pdpClient(config: AppConfig): PdpClient = PdpClient(
    environment = config.altinnEnvironment,
    subscriptionKey = config.altinnSubscriptionKey,
    maskinportenClientId = config.maskinportenClientId,
    maskinportenKey = config.maskinportenKey,
    httpClient = JavaOutgoingHttpClient(
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
    val timeToLive = Duration.ofSeconds(long("decisionCache.timeToLiveSeconds", default = 60, minimum = 1))
    val defaultMaxAge = Duration.ofSeconds(long("decisionCache.defaultMaxAgeSeconds", default = 0, minimum = 0))
    val maximumSize = long("decisionCache.maximumSize", default = 10_000, minimum = 1)
    if (!enabled) return NoPdpDecisionCache
    return InMemoryPdpDecisionCache(timeToLive = timeToLive, defaultMaxAge = defaultMaxAge, maximumSize = maximumSize)
}

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
