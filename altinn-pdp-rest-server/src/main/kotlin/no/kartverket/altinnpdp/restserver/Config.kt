package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import java.io.File

internal class AppConfig(
    val port: Int,
    val accessLog: Boolean,
    val altinnEnvironment: AltinnEnvironment,
    val altinnSubscriptionKey: String,
    val maskinportenClientId: String,
    val maskinportenKey: MaskinportenKey,
    val decisionCache: PdpDecisionCache,
) {
    companion object {
        fun load(config: ApplicationConfig = loadApplicationConfig()): AppConfig {
            val altinnEnvironment = config.enum<AltinnEnvironment>("altinn.environment")
            return AppConfig(
                port = config.port("server.port"),
                accessLog = config.boolean("accessLog.enabled", default = true),
                altinnEnvironment = altinnEnvironment,
                altinnSubscriptionKey = config.required("altinn.subscriptionKey"),
                maskinportenClientId = config.required("maskinporten.clientId"),
                maskinportenKey = MaskinportenKey.parse(config.required("maskinporten.clientJwk")),
                decisionCache = config.decisionCache(altinnEnvironment),
            )
        }
    }
}

internal fun loadApplicationConfig(
    configFile: String? = System.getProperty("APPLICATION_CONFIG_FILE") ?: System.getenv("APPLICATION_CONFIG_FILE"),
): ApplicationConfig {
    val path = configFile?.trim()?.takeIf { it.isNotEmpty() }
        ?: return YamlConfig("application.yaml") ?: error("application.yaml is not on the classpath")
    val file = File(path)
    check(file.isFile) { "APPLICATION_CONFIG_FILE must point to a file, but was \"$path\" (see .env.example)" }
    check(file.extension in setOf("yaml", "yml")) {
        "APPLICATION_CONFIG_FILE must end in .yaml or .yml, but was \"$path\" (see .env.example)"
    }
    return checkNotNull(YamlConfig(file.absolutePath))
}

internal fun ApplicationConfig.optional(path: String): String? =
    propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

internal fun ApplicationConfig.required(path: String): String =
    optional(path) ?: error("Missing required configuration $path (see .env.example)")

internal fun ApplicationConfig.boolean(path: String, default: Boolean): Boolean {
    val raw = optional(path) ?: return default
    return raw.toBooleanStrictOrNull()
        ?: error("$path must be true or false, but was \"$raw\" (see .env.example)")
}

internal fun ApplicationConfig.long(path: String, default: Long, minimum: Long): Long {
    val raw = optional(path) ?: return default
    return raw.toLongOrNull()?.takeIf { it >= minimum }
        ?: error("$path must be a whole number of at least $minimum, but was \"$raw\" (see .env.example)")
}

internal inline fun <reified T : Enum<T>> ApplicationConfig.enum(path: String): T = parseEnum(path, required(path))

internal inline fun <reified T : Enum<T>> ApplicationConfig.enum(path: String, default: T): T =
    optional(path)?.let { parseEnum(path, it) } ?: default

internal inline fun <reified T : Enum<T>> parseEnum(path: String, raw: String): T =
    enumValues<T>().find { it.name == raw }
        ?: error("$path must be one of ${enumValues<T>().joinToString()}, but was \"$raw\" (see .env.example)")

internal fun ApplicationConfig.port(path: String): Int {
    val raw = required(path)
    return raw.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: error("$path must be a port number, but was \"$raw\" (see .env.example)")
}

internal fun ApplicationConfig.file(path: String): String? {
    val raw = optional(path) ?: return null
    check(File(raw).isFile) { "$path must point to a file, but was \"$raw\" (see .env.example)" }
    return raw
}
