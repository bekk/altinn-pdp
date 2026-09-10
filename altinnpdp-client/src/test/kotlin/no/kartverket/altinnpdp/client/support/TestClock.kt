package no.kartverket.altinnpdp.client.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A fixed instant every test in this module measures expiry against. */
internal val NOW: Instant = Instant.parse("2026-01-01T12:00:00Z")

internal fun fixedClock(instant: Instant = NOW): Clock = Clock.fixed(instant, ZoneOffset.UTC)

/** A [Clock] tests can move forward, for asserting what happens as a token approaches expiry. */
internal class MutableClock(
    private var current: Instant = NOW,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
