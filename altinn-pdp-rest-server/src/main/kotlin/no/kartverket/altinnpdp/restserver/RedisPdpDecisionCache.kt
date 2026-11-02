package no.kartverket.altinnpdp.restserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpObligation
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import org.slf4j.LoggerFactory
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.RedisClient
import redis.clients.jedis.SslOptions
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.util.JedisURIHelper
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class DecisionCacheFailureMode { BYPASS, REJECT }

class DecisionCacheUnavailableException(cause: Throwable) : RuntimeException("The decision cache is unavailable", cause)

class RedisPdpDecisionCache(
    url: String,
    keySecret: String,
    private val timeToLive: Duration,
    private val defaultMaxAge: Duration,
    timeout: Duration,
    private val failureMode: DecisionCacheFailureMode,
    private val clock: Clock = Clock.systemUTC(),
) : PdpDecisionCache {

    private val redis: RedisClient = redisUri(url).let { uri ->
        RedisClient.builder()
            .hostAndPort(JedisURIHelper.getHostAndPort(uri))
            .clientConfig(
                DefaultJedisClientConfig.builder()
                    .user(JedisURIHelper.getUser(uri))
                    .password(JedisURIHelper.getPassword(uri))
                    .database(JedisURIHelper.getDBIndex(uri))
                    .apply { if (JedisURIHelper.isRedisSSLScheme(uri)) sslOptions(SslOptions.defaults()) }
                    .connectionTimeoutMillis(timeout.toMillis().toInt())
                    .socketTimeoutMillis(timeout.toMillis().toInt())
                    .build(),
            )
            .poolConfig(ConnectionPoolConfig().apply { setMaxWait(timeout) })
            .build()
    }

    private val keySpec = SecretKeySpec(keySecret.toByteArray(), HMAC_ALGORITHM)

    private val available = AtomicBoolean(true)

    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge {
        val acceptedAge = minOf(maxAge ?: defaultMaxAge, timeToLive)
        val redisKey = redisKey(key)
        var readFailed = false

        if (acceptedAge > Duration.ZERO) {
            val cached = try {
                read(redisKey)
            } catch (e: JedisException) {
                markUnavailable(e)
                if (failureMode == DecisionCacheFailureMode.REJECT) throw DecisionCacheUnavailableException(e)
                readFailed = true
                null
            }
            if (cached != null) {
                val age = ageSince(Instant.ofEpochMilli(cached.startedAt))
                if (age < acceptedAge) return AuthorizationWithAge(cached.toAuthorization(), age)
            }
        }

        val startedAt = clock.instant()
        val authorization = load()
        if (!readFailed) {
            if (authorization.decision == PdpDecision.INDETERMINATE) {
                write("removed from") { redis.del(redisKey) }
            } else {
                val value = Json.encodeToString(CachedDecision.of(authorization, startedAt))
                write("saved in") { redis.set(redisKey, value, SetParams().px(timeToLive.toMillis())) }
            }
        }
        return AuthorizationWithAge(authorization, ageSince(startedAt))
    }

    override fun close() = redis.close()

    private suspend fun read(redisKey: String): CachedDecision? =
        withContext(Dispatchers.IO) { redis.get(redisKey) }.also { markAvailable() }?.let(::decode)

    private fun decode(value: String): CachedDecision? = try {
        Json.decodeFromString<CachedDecision>(value)
    } catch (_: SerializationException) {
        log.warn("A cached decision could not be read, so Altinn is asked instead")
        null
    }

    private suspend fun write(action: String, command: () -> Unit) {
        try {
            withContext(Dispatchers.IO) { command() }
        } catch (e: JedisException) {
            log.debug("A decision could not be $action the decision cache: ${e.message}")
        }
    }

    private fun markAvailable() {
        if (available.compareAndSet(false, true)) log.info("The decision cache is available again")
    }

    private fun markUnavailable(e: JedisException) {
        if (available.compareAndSet(true, false)) {
            log.warn("The decision cache is unavailable until Redis answers again: ${e.message}")
        }
    }

    private fun redisKey(key: AuthorizeRequest.Parsed): String {
        val subject = when (val subject = key.subject) {
            is PersonId -> "person:${subject.value}"
            is SystemUserId -> "systemuser:${subject.value}"
        }
        val question = Json.encodeToString(
            listOf(subject, key.resourceId.value, key.customerOrganizationNumber.value, key.action.value),
        )
        val hmac = Mac.getInstance(HMAC_ALGORITHM).apply { init(keySpec) }.doFinal(question.toByteArray())
        return KEY_PREFIX + HexFormat.of().formatHex(hmac)
    }

    private fun ageSince(startedAt: Instant): Duration =
        Duration.between(startedAt, clock.instant()).coerceAtLeast(Duration.ZERO)

    @Serializable
    private class CachedDecision(
        val decision: PdpDecision,
        val statusCode: String?,
        val obligations: List<CachedObligation>,
        val startedAt: Long,
    ) {
        fun toAuthorization() = PdpAuthorization(
            decision = decision,
            statusCode = statusCode,
            obligations = obligations.map { PdpObligation(it.id, it.category, it.value) },
        )

        companion object {
            fun of(authorization: PdpAuthorization, startedAt: Instant) = CachedDecision(
                decision = authorization.decision,
                statusCode = authorization.statusCode,
                obligations = authorization.obligations.map { CachedObligation(it.id, it.category, it.value) },
                startedAt = startedAt.toEpochMilli(),
            )
        }
    }

    @Serializable
    private class CachedObligation(val id: String?, val category: String, val value: String)

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_PREFIX = "altinn-pdp:decision:v1:"

        val log = LoggerFactory.getLogger(RedisPdpDecisionCache::class.java)

        fun redisUri(url: String): URI {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                null
            }
            return uri?.takeIf(JedisURIHelper::isValid)
                ?: error("decisionCache.redis.url is not a valid Redis URL (see .env.example)")
        }
    }
}
