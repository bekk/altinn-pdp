package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import no.bekk.bekkopen.person.FodselsnummerValidator
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.JavaOutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import java.net.http.HttpClient
import java.time.Duration

internal fun pdpClient(config: AppConfig): PdpClient = PdpClient(
    environment = config.altinnEnvironment,
    subscriptionKey = config.altinnSubscriptionKey,
    maskinportenClientId = config.maskinportenClientId,
    maskinportenKey = config.maskinportenKey,
    httpClient = httpClient(),
)

private fun httpClient(): OutgoingHttpClient = LoggingOutgoingHttpClient(
    JavaOutgoingHttpClient(
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        REQUEST_TIMEOUT,
    ),
)

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

internal fun configurePidValidation(environment: AltinnEnvironment) {
    FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS = environment == AltinnEnvironment.TT02
}

internal fun ApplicationConfig.decisionCache(environment: AltinnEnvironment): PdpDecisionCache {
    val enabled = boolean("decisionCache.enabled", default = false)
    val timeToLive = Duration.ofSeconds(long("decisionCache.timeToLiveSeconds", default = 60, minimum = 1))
    val defaultMaxAge = Duration.ofSeconds(long("decisionCache.defaultMaxAgeSeconds", default = 0, minimum = 0))
    val maximumSize = long("decisionCache.maximumSize", default = 10_000, minimum = 1)
    val type = enum("decisionCache.type", default = DecisionCacheType.IN_MEMORY)
    val redisTimeout = Duration.ofMillis(long("decisionCache.redis.timeoutMillis", default = 100, minimum = 1))
    val redisFailureMode = enum("decisionCache.redis.failureMode", default = DecisionCacheFailureMode.BYPASS)
    if (!enabled) return NoPdpDecisionCache
    return when (type) {
        DecisionCacheType.IN_MEMORY -> InMemoryPdpDecisionCache(
            timeToLive = timeToLive,
            defaultMaxAge = defaultMaxAge,
            maximumSize = maximumSize,
        )

        DecisionCacheType.REDIS -> RedisPdpDecisionCache(
            url = required("decisionCache.redis.url"),
            keySecret = redisKeySecret("decisionCache.redis.keySecret"),
            environment = environment,
            timeToLive = timeToLive,
            defaultMaxAge = defaultMaxAge,
            timeout = redisTimeout,
            failureMode = redisFailureMode,
        )
    }
}

private fun ApplicationConfig.redisKeySecret(path: String): String =
    required(path).takeIf { it.toByteArray().size >= MINIMUM_KEY_SECRET_BYTES }
        ?: error("$path must be at least $MINIMUM_KEY_SECRET_BYTES bytes (see .env.example)")

private const val MINIMUM_KEY_SECRET_BYTES = 32

internal enum class DecisionCacheType { IN_MEMORY, REDIS }
