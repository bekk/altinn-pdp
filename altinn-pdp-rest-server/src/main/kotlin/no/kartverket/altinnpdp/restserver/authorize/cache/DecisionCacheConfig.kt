package no.kartverket.altinnpdp.restserver.authorize.cache

import io.ktor.server.config.ApplicationConfig
import no.kartverket.altinnpdp.restserver.config.boolean
import no.kartverket.altinnpdp.restserver.config.enum
import no.kartverket.altinnpdp.restserver.config.long
import no.kartverket.altinnpdp.restserver.config.required
import java.time.Duration

internal fun ApplicationConfig.decisionCache(): PdpDecisionCache {
    val enabled = boolean("decisionCache.enabled", default = false)
    val timeToLive = Duration.ofSeconds(long("decisionCache.timeToLiveSeconds", default = 60, minimum = 1))
    val defaultMaxAge = Duration.ofSeconds(long("decisionCache.defaultMaxAgeSeconds", default = 0, minimum = 0))
    val maximumSize = long("decisionCache.maximumSize", default = 10_000, minimum = 1)
    val type = enum("decisionCache.type", default = DecisionCacheType.IN_MEMORY)
    val redisTimeout = Duration.ofMillis(
        long("decisionCache.redis.timeoutMillis", default = 100, minimum = 1, maximum = Int.MAX_VALUE.toLong()),
    )
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
            keySecret = required("decisionCache.redis.keySecret"),
            timeToLive = timeToLive,
            defaultMaxAge = defaultMaxAge,
            timeout = redisTimeout,
            failureMode = redisFailureMode,
        )
    }
}

internal enum class DecisionCacheType { IN_MEMORY, REDIS }
