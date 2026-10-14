package no.kartverket.altinnpdp.restserver

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.time.Duration

internal class InvalidCacheControlException : RuntimeException()

/** How old a decision the caller accepts, from `max-age` or `no-cache` in Cache-Control, or null if it does not say. */
internal fun Headers.maxAge(): Duration? =
    getAll(HttpHeaders.CacheControl).orEmpty()
        .flatMap { it.split(',') }
        .mapNotNull { maxAgeOf(it.trim()) }
        .minOrNull()

private fun maxAgeOf(directive: String): Duration? = when (directive.substringBefore('=').lowercase()) {
    "no-cache" -> Duration.ZERO
    "max-age" -> secondsOf(directive.substringAfter('=', missingDelimiterValue = ""))
    else -> null
}

private fun secondsOf(value: String): Duration =
    value.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds) ?: throw InvalidCacheControlException()
