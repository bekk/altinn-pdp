package no.kartverket.altinnpdp.restserver.authorize.cache

import no.kartverket.altinnpdp.restserver.config.DecisionCacheSettings

internal fun DecisionCacheSettings.toDecisionCache(): PdpDecisionCache = when (this) {
    DecisionCacheSettings.Off -> NoPdpDecisionCache

    is DecisionCacheSettings.InMemory -> InMemoryPdpDecisionCache(
        timeToLive = timeToLive,
        defaultMaxAge = defaultMaxAge,
        maximumSize = maximumSize,
    )

    is DecisionCacheSettings.Redis -> RedisPdpDecisionCache(
        url = url,
        keySecret = keySecret,
        timeToLive = timeToLive,
        defaultMaxAge = defaultMaxAge,
        timeout = timeout,
        failureMode = failureMode,
    )
}
