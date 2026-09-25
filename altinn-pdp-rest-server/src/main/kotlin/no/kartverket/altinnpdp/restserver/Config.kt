package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig

// Every value from .env and secrets is read through these, so all are trimmed and errors look the same.
internal fun ApplicationConfig.optional(path: String): String? =
    propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

internal fun ApplicationConfig.required(path: String): String =
    optional(path) ?: error("Missing required configuration $path (see .env.example)")

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

internal fun ApplicationConfig.port(path: String): Int {
    val raw = required(path)
    return raw.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: error("$path must be a port number, but was \"$raw\" (see .env.example)")
}
