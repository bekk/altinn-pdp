package no.kartverket.altinnpdp.restserver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.fppt.jedismock.RedisServer
import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.PdpObligation
import no.kartverket.altinnpdp.client.support.SAMPLE_PID
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RedisPdpDecisionCacheTest {

    private val timeToLive: Duration = Duration.ofMinutes(5)
    private val defaultMaxAge: Duration = Duration.ofMinutes(1)
    private val clock = TestClock()
    private val redis: RedisServer = RedisServer.newRedisServer().start()
    private val caches = mutableListOf<RedisPdpDecisionCache>()
    private val cache = cache()
    private val loads = AtomicInteger()

    private val permit: suspend () -> PdpAuthorization = {
        loads.incrementAndGet()
        PdpAuthorization(PdpDecision.PERMIT)
    }

    @AfterTest
    fun stop() {
        caches.forEach { it.close() }
        redis.stop()
    }

    @Test
    fun `serves the cached decision while it is younger than the max age`() = runBlocking {
        val maxAge = Duration.ofSeconds(30)
        lookup(maxAge)
        clock.advance(maxAge.minusMillis(1))
        lookup(maxAge)
        assertEquals(1, loads.get(), "one millisecond left, so still cached")

        clock.advance(Duration.ofMillis(1))
        lookup(maxAge)
        assertEquals(2, loads.get())
    }

    @Test
    fun `uses the default max age when the caller gives none`() = runBlocking {
        lookup()
        clock.advance(defaultMaxAge.minusMillis(1))
        lookup()
        assertEquals(1, loads.get())

        clock.advance(Duration.ofMillis(1))
        lookup()
        assertEquals(2, loads.get())
    }

    @Test
    fun `a max age of zero always loads`() = runBlocking {
        lookup()
        lookup(Duration.ZERO)
        lookup(Duration.ZERO)

        assertEquals(3, loads.get())
    }

    @Test
    fun `a max age above the time to live is capped to the time to live`() = runBlocking {
        val maxAge = timeToLive.multipliedBy(10)
        lookup(maxAge)
        clock.advance(timeToLive.minusMillis(1))
        lookup(maxAge)
        assertEquals(1, loads.get())

        clock.advance(Duration.ofMillis(1))
        lookup(maxAge)
        assertEquals(2, loads.get())
    }

    @Test
    fun `a decision loaded for one caller is served to the others`() = runBlocking {
        lookup()
        clock.advance(Duration.ofSeconds(10))
        lookup(Duration.ZERO) { loads.incrementAndGet(); PdpAuthorization(PdpDecision.DENY) }

        assertEquals(PdpDecision.DENY, lookup().authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `a decision saved by one pod is served by another`() = runBlocking {
        lookup()

        assertEquals(PdpDecision.PERMIT, cache().get(key(), maxAge = null, load = permit).authorization.decision)
        assertEquals(1, loads.get())
    }

    @Test
    fun `a refresh on a pod that has never read the cache reaches the other pods`() = runBlocking {
        lookup()
        clock.advance(Duration.ofSeconds(10))
        cache().get(key(), Duration.ZERO) { loads.incrementAndGet(); PdpAuthorization(PdpDecision.DENY) }

        assertEquals(PdpDecision.DENY, lookup().authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `keeps the status code and obligations`() = runBlocking {
        val authorization = PdpAuthorization(
            decision = PdpDecision.PERMIT,
            statusCode = "urn:oasis:names:tc:xacml:1.0:status:ok",
            obligations = listOf(PdpObligation("level", "urn:altinn:minimum-authenticationlevel", "3")),
        )
        lookup { authorization }

        assertEquals(authorization, lookup().authorization)
    }

    @Test
    fun `reports how long ago the cached decision was loaded`() = runBlocking {
        assertEquals(Duration.ZERO, lookup().age)

        clock.advance(Duration.ofSeconds(30))
        assertEquals(Duration.ofSeconds(30), lookup().age)
    }

    @Test
    fun `the age counts from when Altinn was asked, not from when it answered`() = runBlocking {
        val slow = lookup {
            loads.incrementAndGet()
            clock.advance(Duration.ofSeconds(2))
            PdpAuthorization(PdpDecision.PERMIT)
        }

        assertEquals(Duration.ofSeconds(2), slow.age)
    }

    @Test
    fun `a decision from a pod whose clock is ahead is never given a negative age`() = runBlocking {
        lookup()
        clock.advance(Duration.ofSeconds(-3))

        assertEquals(Duration.ZERO, lookup().age)
    }

    @Test
    fun `keeps a separate decision for each key`() = runBlocking {
        configurePidValidation(AltinnEnvironment.TT02)
        lookup(key = key())
        lookup(key = key(systemuserId = "8f2b1c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
        lookup(key = key(pid = SAMPLE_PID))
        lookup(key = key(pid = "01017012343"))
        lookup(key = key(resourceId = "another-resource"))
        lookup(key = key(customerOrganizationNumber = "974760673"))
        lookup(key = key(action = "write"))

        assertEquals(7, loads.get())
    }

    @Test
    fun `the Redis key covers every field of the question`() {
        // A field added to Parsed must also be added to redisKey(), or two questions would share a decision.
        assertEquals(
            listOf("subject", "resourceId", "customerOrganizationNumber", "action"),
            AuthorizeRequest.Parsed::class.java.declaredFields.map { it.name },
        )
    }

    @Test
    fun `keeps the decisions of each Altinn environment apart`() = runBlocking {
        cache(environment = AltinnEnvironment.TT02).get(key(), maxAge = null, load = permit)
        cache(environment = AltinnEnvironment.PROD).get(key(), maxAge = null, load = permit)

        assertEquals(2, loads.get())
    }

    @Test
    fun `Redis sees neither the national identity number nor any other value from the request`() = runBlocking {
        configurePidValidation(AltinnEnvironment.TT02)
        lookup(key = key(pid = SAMPLE_PID))

        val stored = redisContents()
        for (value in listOf(SAMPLE_PID, "test-resource", "923609016", "read")) {
            assertFalse(stored.contains(value), "found $value in $stored")
        }
    }

    @Test
    fun `the key depends on the secret`() = runBlocking {
        lookup()
        cache(keySecret = "another-secret").get(key(), maxAge = null, load = permit)

        assertEquals(2, loads.get())
        assertEquals(2, redisContents().lines().size)
    }

    @Test
    fun `asks Altinn when the cached decision cannot be read, and replaces it`() = runBlocking {
        lookup()
        overwriteEveryValue("not a decision")

        assertEquals(PdpDecision.PERMIT, lookup().authorization.decision)
        lookup()
        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep a failed load`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            lookup { loads.incrementAndGet(); error("Altinn is down") }
        }
        lookup()

        assertEquals(2, loads.get())
    }

    @Test
    fun `does not keep an indeterminate decision`() = runBlocking {
        val indeterminate = lookup { loads.incrementAndGet(); PdpAuthorization(PdpDecision.INDETERMINATE) }
        lookup()

        assertEquals(PdpDecision.INDETERMINATE, indeterminate.authorization.decision)
        assertEquals(2, loads.get())
    }

    @Test
    fun `asks Altinn when Redis is down`() = runBlocking {
        val down = cache(url = "redis://localhost:${unusedPort()}")

        assertEquals(PdpDecision.PERMIT, down.get(key(), maxAge = null, load = permit).authorization.decision)
        assertEquals(1, loads.get())
    }

    @Test
    fun `refuses the call when Redis is down and the failure mode is REJECT`() = runBlocking {
        val down = cache(url = "redis://localhost:${unusedPort()}", failureMode = DecisionCacheFailureMode.REJECT)

        assertFailsWith<DecisionCacheUnavailableException> { down.get(key(), maxAge = null, load = permit) }
        assertEquals(0, loads.get())
    }

    @Test
    fun `a save that fails does not fail the call, even when the failure mode is REJECT`() = runBlocking {
        val own = RedisServer.newRedisServer().start()
        val cache = cache(url = "redis://${own.host}:${own.bindPort}", failureMode = DecisionCacheFailureMode.REJECT)

        // Redis answers the read, and is gone by the time the decision is saved.
        val authorization = cache.get(key(), maxAge = null) { own.stop(); permit() }

        assertEquals(PdpDecision.PERMIT, authorization.authorization.decision)
    }

    @Test
    fun `a max age of zero asks Altinn even when Redis is down and the failure mode is REJECT`() = runBlocking {
        val down = cache(url = "redis://localhost:${unusedPort()}", failureMode = DecisionCacheFailureMode.REJECT)

        assertEquals(PdpDecision.PERMIT, down.get(key(), Duration.ZERO, permit).authorization.decision)
    }

    @Test
    fun `starts caching once Redis is up, even if it was down at first`() = runBlocking {
        val port = unusedPort()
        val cache = cache(url = "redis://localhost:$port")
        cache.get(key(), maxAge = null, load = permit)

        val late = RedisServer.newRedisServer(port).start()
        try {
            cache.get(key(), maxAge = null, load = permit)
            cache.get(key(), maxAge = null, load = permit)
        } finally {
            late.stop()
        }
        assertEquals(2, loads.get(), "the first while Redis was down, the second once it was up")
    }

    @Test
    fun `logs once when Redis becomes unavailable, and once when it is back`() = runBlocking {
        val logger = LoggerFactory.getLogger(RedisPdpDecisionCache::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val port = unusedPort()
            val cache = cache(url = "redis://localhost:$port")
            repeat(3) { cache.get(key(), maxAge = null, load = permit) }

            val late = RedisServer.newRedisServer(port).start()
            try {
                repeat(3) { cache.get(key(), maxAge = null, load = permit) }
            } finally {
                late.stop()
            }
        } finally {
            logger.detachAppender(appender)
        }
        assertEquals(listOf(Level.WARN, Level.INFO), appender.list.map { it.level })
    }

    @Test
    fun `a lookup does not wait for a Redis that never answers longer than the timeout`() = runBlocking {
        // The socket accepts the connection but never answers, like a Redis behind a stalled network.
        ServerSocket(0).use { silent ->
            val cache = cache(url = "redis://localhost:${silent.localPort}")

            val authorization = withTimeout(5_000) { cache.get(key(), maxAge = null, load = permit) }

            assertEquals(PdpDecision.PERMIT, authorization.authorization.decision)
        }
    }

    private suspend fun lookup(
        maxAge: Duration? = null,
        key: AuthorizeRequest.Parsed = key(),
        load: suspend () -> PdpAuthorization = permit,
    ): AuthorizationWithAge = cache.get(key, maxAge, load)

    private fun cache(
        url: String = "redis://${redis.host}:${redis.bindPort}",
        keySecret: String = "test-secret",
        environment: AltinnEnvironment = AltinnEnvironment.TT02,
        failureMode: DecisionCacheFailureMode = DecisionCacheFailureMode.BYPASS,
    ) = RedisPdpDecisionCache(
        url = url,
        keySecret = keySecret,
        environment = environment,
        timeToLive = timeToLive,
        defaultMaxAge = defaultMaxAge,
        timeout = Duration.ofSeconds(1),
        failureMode = failureMode,
        clock = clock,
    ).also { caches += it }

    private fun key(
        systemuserId: String = SAMPLE_SYSTEMUSER_ID,
        pid: String? = null,
        resourceId: String = "test-resource",
        customerOrganizationNumber: String = "923609016",
        action: String = "read",
    ) = AuthorizeRequest(
        systemuserId = systemuserId.takeIf { pid == null },
        pid = pid,
        resourceId = resourceId,
        customerOrganizationNumber = customerOrganizationNumber,
        action = action,
    ).parse()

    private fun overwriteEveryValue(value: String) {
        val client = RedisClient.create("redis://${redis.host}:${redis.bindPort}")
        try {
            val commands = client.connect().sync()
            commands.keys("*").forEach { commands.set(it, value) }
        } finally {
            client.shutdown()
        }
    }

    // Every key and value in Redis, one key and its value per line.
    private fun redisContents(): String {
        val client = RedisClient.create("redis://${redis.host}:${redis.bindPort}")
        try {
            val commands = client.connect().sync()
            return commands.keys("*").sorted().joinToString("\n") { "$it ${commands.get(it)}" }
        } finally {
            client.shutdown()
        }
    }

    private fun unusedPort(): Int = ServerSocket(0).use { it.localPort }

    private class TestClock : Clock() {
        private var now = Instant.parse("2026-01-01T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?) = this

        fun advance(duration: Duration) {
            now += duration
        }
    }
}
