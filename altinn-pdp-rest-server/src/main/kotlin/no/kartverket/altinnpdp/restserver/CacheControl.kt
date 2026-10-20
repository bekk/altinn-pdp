package no.kartverket.altinnpdp.restserver

import io.ktor.http.HeaderValueParam
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import java.time.Duration

internal class InvalidCacheControlException : RuntimeException()

/** How old a decision the caller accepts, from `max-age` or `no-cache` in Cache-Control, or null if it does not say. */
internal fun Headers.maxAge(): Duration? =
    getAll(HttpHeaders.CacheControl).orEmpty()
        // Unquotes arguments and ignores the commas inside them, so `max-age="30"` is read as 30 (RFC 9111 §5.2).
        .flatMap { parseHeaderValue(it, parametersOnly = true) }
        .flatMap { it.params }
        .mapNotNull(::maxAgeOf)
        .minOrNull()

private fun maxAgeOf(directive: HeaderValueParam): Duration? = when (directive.name.lowercase()) {
    "no-cache" -> Duration.ZERO
    "max-age" -> secondsOf(directive.value)
    else -> null
}

private fun secondsOf(value: String): Duration =
    value.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds) ?: throw InvalidCacheControlException()
