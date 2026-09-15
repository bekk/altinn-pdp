package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient

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
    config.propertyOrNull("maskinporten.tokenUrl")?.getString()?.takeIf { it.isNotBlank() }
        ?.let { builder.maskinportenTokenUrl(it) }
    return builder.build()
}

// Ktor's "$VAR" substitution rejects a variable that is unset, but not one exported as an empty
// string - which is exactly what a freshly copied .env gives you.
private fun ApplicationConfig.required(path: String): String =
    property(path).getString().takeIf { it.isNotBlank() }
        ?: error("Missing required configuration $path (see .env.example)")
