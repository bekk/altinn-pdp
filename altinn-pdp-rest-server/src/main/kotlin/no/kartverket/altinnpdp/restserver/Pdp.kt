package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient

/**
 * Routes resolve the [PdpClient] with `val pdpClient: PdpClient by dependencies`. Pass [client]
 * explicitly in tests instead of setting up real Maskinporten credentials.
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
    Dotenv.get("MASKINPORTEN_TOKEN_URL")?.let { builder.maskinportenTokenUrl(it) }
    return builder.build()
}

private fun requiredEnv(name: String): String =
    Dotenv.get(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable $name (see .env.example)")
