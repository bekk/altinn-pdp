package no.kartverket.altinnpdp.restserver

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.Delay
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpObligation
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What the server does when Redis cannot be read: ask Altinn without the cache, or refuse the call. */
enum class DecisionCacheFailureMode { BYPASS, REJECT }

class DecisionCacheUnavailableException(cause: Throwable) : RuntimeException("The decision cache is unavailable", cause)

/**
 * Keeps decisions in Redis, shared by every pod. Redis only sees an HMAC of the question, never a national identity
 * number or any other value from the request.
 */
class RedisPdpDecisionCache internal constructor(
    url: String,
    keySecret: String,
    environment: AltinnEnvironment,
    private val timeToLive: Duration,
    private val defaultMaxAge: Duration,
    timeout: Duration,
    private val failureMode: DecisionCacheFailureMode,
    private val clock: Clock,
) : PdpDecisionCache {

    constructor(
        url: String,
        keySecret: String,
        environment: AltinnEnvironment,
        timeToLive: Duration,
        defaultMaxAge: Duration,
        timeout: Duration,
        failureMode: DecisionCacheFailureMode,
    ) : this(
        url,
        keySecret,
        environment,
        timeToLive,
        defaultMaxAge,
        timeout,
        failureMode,
        Clock.systemUTC(),
    )

    // The URI's timeout bounds the handshake that follows the socket connect, which Lettuce otherwise lets run for 60
    // seconds.
    private val uri = RedisURI.create(url).also { it.timeout = timeout }

    // Created on first use, since the client starts its threads and logging as soon as it exists.
    private val lazyResources = lazy {
        ClientResources.builder()
            .reconnectDelay(Delay.exponential(MIN_RECONNECT_DELAY, MAX_RECONNECT_DELAY, 2, TimeUnit.MILLISECONDS))
            .build()
    }
    private val lazyClient = lazy {
        RedisClient.create(lazyResources.value).apply {
            options = ClientOptions.builder()
                // A Redis that is down fails each command at once, and a slow one after the timeout, instead of
                // queueing.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build()
        }
    }
    private val client by lazyClient

    private val keySpec = SecretKeySpec(keySecret.toByteArray(), HMAC_ALGORITHM)

    // Kept apart per Altinn environment, so TT02 and PROD never share a decision, even in one Redis. Change the version
    // when CachedDecision changes, so pods on different versions do not read each other's entries.
    private val keyPrefix = "altinn-pdp:${environment.name.lowercase()}:decision:v1:"

    private var connection: CompletableFuture<StatefulRedisConnection<String, String>>? = null

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
            } catch (e: RedisException) {
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
        // Not saved when the read just failed, so a lookup never waits for a Redis that is down twice.
        if (!readFailed && authorization.decision != PdpDecision.INDETERMINATE) {
            save(redisKey, CachedDecision.of(authorization, startedAt))
        }
        return AuthorizationWithAge(authorization, ageSince(startedAt))
    }

    override fun close() {
        if (lazyClient.isInitialized()) client.shutdown()
        if (lazyResources.isInitialized()) lazyResources.value.shutdown().get()
    }

    private suspend fun read(redisKey: String): CachedDecision? =
        commands().get(redisKey).await().also { markAvailable() }?.let(::decode)

    // An entry that cannot be read counts as a miss, and the new decision replaces it.
    private fun decode(value: String): CachedDecision? = try {
        Json.decodeFromString<CachedDecision>(value)
    } catch (_: SerializationException) {
        log.warn("A cached decision could not be read, so Altinn is asked instead")
        null
    }

    // Connects first if no read has, so that a pod that only gets max-age=0 or no-cache calls still saves for the
    // others. A failed save does not mark Redis as unavailable, since reads decide that, and logging it would repeat on
    // every miss.
    private suspend fun save(redisKey: String, decision: CachedDecision) {
        try {
            commands().set(redisKey, Json.encodeToString(decision), SetArgs().px(timeToLive.toMillis())).await()
        } catch (e: RedisException) {
            log.debug("A decision could not be saved in the decision cache: ${e.message}")
        }
    }

    // Logs when Redis cannot be read and when it can again, not on every lookup in between.
    private fun markAvailable() {
        if (available.compareAndSet(false, true)) log.info("The decision cache is available again")
    }

    private fun markUnavailable(e: RedisException) {
        if (available.compareAndSet(true, false)) {
            log.warn("The decision cache is unavailable until Redis answers again: ${e.message}")
        }
    }

    private suspend fun commands(): RedisAsyncCommands<String, String> = connection().asDeferred().await().async()

    // Connects on first use, and again after a failed attempt, so the server starts even when Redis is down. Once
    // connected, Lettuce reconnects by itself, and commands fail at once while it is disconnected.
    @Synchronized
    private fun connection(): CompletableFuture<StatefulRedisConnection<String, String>> =
        connection?.takeUnless { it.isCompletedExceptionally }
            ?: client.connectAsync(StringCodec.UTF8, uri).toCompletableFuture().also { connection = it }

    // Every field of the question must be in the HMAC input. A test fails when Parsed gets a new field, as a reminder.
    private fun redisKey(key: AuthorizeRequest.Parsed): String {
        val subject = when (val subject = key.subject) {
            is PersonId -> "person:${subject.value}"
            is SystemUserId -> "systemuser:${subject.value}"
        }
        val question = Json.encodeToString(
            listOf(subject, key.resourceId.value, key.customerOrganizationNumber.value, key.action.value),
        )
        val hmac = Mac.getInstance(HMAC_ALGORITHM).apply { init(keySpec) }.doFinal(question.toByteArray())
        return keyPrefix + HexFormat.of().formatHex(hmac)
    }

    // Pods' clocks may differ a little, so a decision saved by another pod can look like it is from the future.
    private fun ageSince(startedAt: Instant): Duration =
        Duration.between(startedAt, clock.instant()).coerceAtLeast(Duration.ZERO)

    @Serializable
    private class CachedDecision(
        val decision: PdpDecision,
        val statusCode: String?,
        val obligations: List<CachedObligation>,
        // Epoch milliseconds of when Altinn was asked, since Altinn may answer from any moment after that.
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

        val MIN_RECONNECT_DELAY: Duration = Duration.ofMillis(100)
        val MAX_RECONNECT_DELAY: Duration = Duration.ofSeconds(2)

        val log = LoggerFactory.getLogger(RedisPdpDecisionCache::class.java)
    }
}
