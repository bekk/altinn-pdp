package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import java.time.Duration
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.Timeouts

/**
 * Registers the [PdpClient] every `/authorize` call resolves via Ktor's DI plugin
 * (`val pdpClient: PdpClient by dependencies` in a route). Defaults to one built from
 * environment variables / `.env` (see `.env.example`) - pass [client] explicitly in tests instead
 * of setting up real Maskinporten credentials.
 */
fun Application.configurePdp(client: PdpClient = pdpClientFromEnv()) {
    install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun pdpClientFromEnv(): PdpClient {
    val builder = PdpClient.builder()
        .environment(AltinnEnvironment.valueOf(Dotenv.get("ALTINN_ENVIRONMENT") ?: "TT02"))
        .subscriptionKey(requiredEnv("ALTINN_SUBSCRIPTION_KEY"))
        .maskinportenClientId(requiredEnv("MASKINPORTEN_CLIENT_ID"))
        .maskinportenJwk(requiredEnv("MASKINPORTEN_CLIENT_JWK"))
        .timeouts(timeoutsFromEnv())
    Dotenv.get("MASKINPORTEN_TOKEN_URL")?.let { builder.maskinportenTokenUrl(it) }
    return builder.build()
}

internal fun timeoutsFromEnv(lookup: (String) -> String? = Dotenv::get): Timeouts = Timeouts(
    connect = millis("ALTINN_CONNECT_TIMEOUT_MS", CONNECT, lookup),
    request = millis("ALTINN_REQUEST_TIMEOUT_MS", REQUEST, lookup),
    total = millis("ALTINN_TOTAL_TIMEOUT_MS", TOTAL, lookup),
)

private const val CONNECT = 2_000L
private const val REQUEST = 4_000L
private const val TOTAL = 8_000L

internal val RESPONSE_BUDGET: Duration = Duration.ofSeconds(10)

private fun millis(name: String, default: Long, lookup: (String) -> String?): Duration {
    val raw = lookup(name)?.takeIf { it.isNotBlank() } ?: return Duration.ofMillis(default)
    val value = raw.trim().toLongOrNull()
        ?: error("$name must be a whole number of milliseconds, but was \"$raw\"")
    require(value > 0) { "$name must be positive, but was $value" }
    return Duration.ofMillis(value)
}

private fun requiredEnv(name: String): String =
    Dotenv.get(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable $name (see .env.example)")
