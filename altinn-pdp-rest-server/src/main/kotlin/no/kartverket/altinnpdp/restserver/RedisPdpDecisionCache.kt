package no.kartverket.altinnpdp.restserver

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
    private val timeout: Duration,
    connectTimeout: Duration,
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
        connectTimeout: Duration,
        failureMode: DecisionCacheFailureMode,
    ) : this(
        url,
        keySecret,
        environment,
        timeToLive,
        defaultMaxAge,
        timeout,
        connectTimeout,
        failureMode,
        Clock.systemUTC(),
    )

    // The URI's timeout also bounds the handshake that follows the socket connect.
    private val uri = RedisURI.create(url).also { it.timeout = connectTimeout }

    private val client = RedisClient.create().apply {
        options = ClientOptions.builder()
            // A Redis that is down fails each command at once, and a slow one after the timeout, instead of queueing.
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .timeoutOptions(TimeoutOptions.enabled(timeout))
            .build()
    }

    private val keySpec = SecretKeySpec(keySecret.toByteArray(), HMAC_ALGORITHM)

    // Kept apart per Altinn environment, so TT02 and PROD never share a decision, even in one Redis. Change the version
    // when CachedDecision changes, so pods on different versions do not read each other's entries.
    private val keyPrefix = "altinn-pdp:${environment.name.lowercase()}:decision:v1:"

    // Altinn may answer from any moment after the call starts, so the age counts from then.
    private class Load(val startedAt: Instant, val result: Deferred<PdpAuthorization>)

    private val loads = ConcurrentHashMap<String, Load>()

    private val loadScope = CoroutineScope(SupervisorJob())

    private var connection: CompletableFuture<StatefulRedisConnection<String, String>>? = null

    @Volatile
    private var connectFailedAt: Instant = Instant.EPOCH

    private val available = AtomicBoolean(true)

    override suspend fun get(
        key: AuthorizeRequest.Parsed,
        maxAge: Duration?,
        load: suspend () -> PdpAuthorization,
    ): AuthorizationWithAge {
        val acceptedAge = minOf(maxAge ?: defaultMaxAge, timeToLive)
        val redisKey = redisKey(key)

        if (acceptedAge > Duration.ZERO) {
            val cached = read(redisKey)
            if (cached != null) {
                val age = ageSince(Instant.ofEpochMilli(cached.startedAt))
                if (age < acceptedAge) return AuthorizationWithAge(cached.toAuthorization(), age)
            }
        }

        // Lookups on this pod that miss at the same time share one call to Altinn, as in InMemoryPdpDecisionCache.
        val pending = loads.compute(redisKey) { _, running ->
            running?.takeIf { it.isYoungerThan(acceptedAge) } ?: start(redisKey, load)
        }!!
        pending.result.start()
        return AuthorizationWithAge(pending.result.await(), ageSince(pending.startedAt))
    }

    override fun close() {
        client.shutdown()
    }

    // The load runs outside the caller, so a caller that gives up neither cancels it for the others nor stops the save.
    private fun start(redisKey: String, load: suspend () -> PdpAuthorization): Load {
        val startedAt = clock.instant()
        val result = loadScope.async(start = CoroutineStart.LAZY) {
            load().also {
                if (it.decision != PdpDecision.INDETERMINATE) save(redisKey, CachedDecision.of(it, startedAt))
            }
        }
        return Load(startedAt, result).also { started ->
            result.invokeOnCompletion { loads.remove(redisKey, started) }
        }
    }

    // A load still in flight is joined only if it started recently enough, so max-age=0 always asks Altinn anew.
    private fun Load.isYoungerThan(age: Duration): Boolean = !result.isCancelled && ageSince(startedAt) < age

    private suspend fun read(redisKey: String): CachedDecision? {
        val value = try {
            commands().get(redisKey).await().also { markAvailable() }
        } catch (e: RedisException) {
            markUnavailable(e)
            if (failureMode == DecisionCacheFailureMode.REJECT) throw DecisionCacheUnavailableException(e)
            return null
        }
        return value?.let(::decode)
    }

    // An entry that cannot be read counts as a miss, and the new decision replaces it.
    private fun decode(value: String): CachedDecision? = try {
        Json.decodeFromString<CachedDecision>(value)
    } catch (_: SerializationException) {
        log.warn("A cached decision could not be read, so Altinn is asked instead")
        null
    }

    private suspend fun save(redisKey: String, decision: CachedDecision) {
        val args = arrayOf(Json.encodeToString(decision), decision.startedAt.toString(), timeToLive.toMillis().toString())
        try {
            val commands = commands()
            try {
                commands.evalsha<Long>(SAVE_SCRIPT_SHA, ScriptOutputType.INTEGER, arrayOf(redisKey), *args).await()
            } catch (_: RedisNoScriptException) {
                // Redis forgets loaded scripts when it restarts. EVAL loads it again.
                commands.eval<Long>(SAVE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(redisKey), *args).await()
            }
            markAvailable()
        } catch (e: RedisException) {
            markUnavailable(e)
        }
    }

    // Logs when Redis goes away and when it is back, not on every lookup in between.
    private fun markAvailable() {
        if (available.compareAndSet(false, true)) log.info("The decision cache is available again")
    }

    private fun markUnavailable(e: RedisException) {
        if (available.compareAndSet(true, false)) {
            log.warn("The decision cache is unavailable until Redis answers again: ${e.message}")
        }
    }

    // Waits for the connection no longer than for a command, while connecting goes on in the background.
    private suspend fun commands(): RedisAsyncCommands<String, String> {
        val connection = withTimeoutOrNull(timeout.toMillis()) { connection().asDeferred().await() }
            ?: throw RedisConnectionException("Not connected to Redis within $timeout")
        return connection.async()
    }

    // Connects on first use, so the server starts even when Redis is down. After a failed attempt, the next waits a
    // while, so a Redis that is down is not asked to connect on every lookup. Once connected, Lettuce reconnects.
    @Synchronized
    private fun connection(): CompletableFuture<StatefulRedisConnection<String, String>> {
        val current = connection
        if (current != null && !(current.isCompletedExceptionally && mayConnectAgain())) return current
        return client.connectAsync(StringCodec.UTF8, uri).toCompletableFuture()
            .whenComplete { _, e -> if (e != null) connectFailedAt = clock.instant() }
            .also { connection = it }
    }

    private fun mayConnectAgain(): Boolean = Duration.between(connectFailedAt, clock.instant()) >= RECONNECT_DELAY

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

        val RECONNECT_DELAY: Duration = Duration.ofSeconds(1)

        // Saves a decision unless Redis holds one that Altinn was asked for later, so a slow, older answer never
        // replaces a newer one. An entry that cannot be read is replaced.
        const val SAVE_SCRIPT = """
            local old = redis.call('GET', KEYS[1])
            if old then
                local ok, cached = pcall(cjson.decode, old)
                if ok and type(cached) == 'table' and tonumber(cached.startedAt)
                    and tonumber(cached.startedAt) > tonumber(ARGV[2]) then
                    return 0
                end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            return 1
        """

        val SAVE_SCRIPT_SHA: String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(SAVE_SCRIPT.toByteArray()))

        val log = LoggerFactory.getLogger(RedisPdpDecisionCache::class.java)
    }
}
