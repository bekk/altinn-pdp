package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

// Ktor's "$VAR" substitution rejects a variable that is unset, but not one exported as an empty
// string - which is exactly what a freshly copied .env gives you.
internal fun ApplicationConfig.optional(path: String): String? =
    propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

internal fun ApplicationConfig.required(path: String): String =
    optional(path) ?: error("Missing required configuration $path (see .env.example)")

internal fun ApplicationConfig.millis(path: String, default: Long): Duration {
    val raw = optional(path) ?: return Duration.ofMillis(default)
    val value = raw.toLongOrNull()
        ?: error("$path must be a whole number of milliseconds, but was \"$raw\" (see .env.example)")
    require(value > 0) { "$path must be positive, but was $value (see .env.example)" }
    return Duration.ofMillis(value)
}

internal fun ApplicationConfig.boolean(path: String, default: Boolean): Boolean {
    val raw = optional(path) ?: return default
    return raw.toBooleanStrictOrNull()
        ?: error("$path must be true or false, but was \"$raw\" (see .env.example)")
}

internal inline fun <reified T : Enum<T>> ApplicationConfig.enum(path: String): T {
    val raw = required(path)
    return enumValues<T>().find { it.name == raw }
        ?: error("$path must be one of ${enumValues<T>().joinToString()}, but was \"$raw\" (see .env.example)")
}
