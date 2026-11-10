package no.kartverket.altinnpdp.restserver.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

internal sealed interface DecisionCacheSettings {
    data object Off : DecisionCacheSettings

    class InMemory(val timeToLive: Duration, val defaultMaxAge: Duration, val maximumSize: Long) : DecisionCacheSettings

    class Redis(
        val url: String,
        val keySecret: String,
        val timeToLive: Duration,
        val defaultMaxAge: Duration,
        val timeout: Duration,
        val failureMode: DecisionCacheFailureMode,
    ) : DecisionCacheSettings
}

enum class DecisionCacheFailureMode { BYPASS, REJECT }

internal fun ApplicationConfig.decisionCacheSettings(): DecisionCacheSettings {
    val enabled = boolean("decisionCache.enabled", default = false)
    val timeToLive = Duration.ofSeconds(long("decisionCache.timeToLiveSeconds", default = 60, minimum = 1))
    val defaultMaxAge = Duration.ofSeconds(long("decisionCache.defaultMaxAgeSeconds", default = 0, minimum = 0))
    val maximumSize = long("decisionCache.maximumSize", default = 10_000, minimum = 1)
    val type = enum("decisionCache.type", default = DecisionCacheType.IN_MEMORY)
    val redisTimeout = Duration.ofMillis(
        long("decisionCache.redis.timeoutMillis", default = 100, minimum = 1, maximum = Int.MAX_VALUE.toLong()),
    )
    val redisFailureMode = enum("decisionCache.redis.failureMode", default = DecisionCacheFailureMode.BYPASS)
    if (!enabled) return DecisionCacheSettings.Off
    return when (type) {
        DecisionCacheType.IN_MEMORY -> DecisionCacheSettings.InMemory(
            timeToLive = timeToLive,
            defaultMaxAge = defaultMaxAge,
            maximumSize = maximumSize,
        )

        DecisionCacheType.REDIS -> DecisionCacheSettings.Redis(
            url = required("decisionCache.redis.url"),
            keySecret = required("decisionCache.redis.keySecret"),
            timeToLive = timeToLive,
            defaultMaxAge = defaultMaxAge,
            timeout = redisTimeout,
            failureMode = redisFailureMode,
        )
    }
}

internal enum class DecisionCacheType { IN_MEMORY, REDIS }
