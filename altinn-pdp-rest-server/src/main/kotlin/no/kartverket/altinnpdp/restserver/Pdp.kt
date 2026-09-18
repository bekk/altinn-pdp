package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.Timeouts

fun Application.configurePdp(client: PdpClient = pdpClientFromConfig()) {
    install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun Application.pdpClientFromConfig(): PdpClient {
    val config = environment.config
    val builder = PdpClient.builder()
        .environment(AltinnEnvironment.valueOf(config.required("altinn.environment")))
        .subscriptionKey(config.required("altinn.subscriptionKey"))
        .maskinportenClientId(config.required("maskinporten.clientId"))
        .maskinportenJwk(config.required("maskinporten.clientJwk"))
        .timeouts(timeoutsFromConfig(config))
    config.optional("maskinporten.tokenUrl")?.let { builder.maskinportenTokenUrl(it) }
    return builder.build()
}

internal fun timeoutsFromConfig(config: ApplicationConfig = MapApplicationConfig()): Timeouts = Timeouts(
    request = config.millis("timeouts.requestMs", REQUEST),
    total = config.millis("timeouts.totalMs", TOTAL),
)

private const val REQUEST = 4_000L
private const val TOTAL = 8_000L

private fun ApplicationConfig.millis(path: String, default: Long): Duration {
    val raw = optional(path) ?: return Duration.ofMillis(default)
    val value = raw.trim().toLongOrNull()
        ?: error("$path must be a whole number of milliseconds, but was \"$raw\" (see .env.example)")
    require(value > 0) { "$path must be positive, but was $value (see .env.example)" }
    return Duration.ofMillis(value)
}

// Ktor's "$VAR" substitution rejects a variable that is unset, but not one exported as an empty
// string - which is exactly what a freshly copied .env gives you.
private fun ApplicationConfig.required(path: String): String =
    optional(path) ?: error("Missing required configuration $path (see .env.example)")

private fun ApplicationConfig.optional(path: String): String? =
    propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
