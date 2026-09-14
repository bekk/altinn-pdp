package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient

/**
 * Registers the [PdpClient] every `/authorize` call resolves via Ktor's DI plugin
 * (`val pdpClient: PdpClient by dependencies` in a route). Defaults to one built from
 * `application.yaml`, whose values come from environment variables - pass [client] explicitly in
 * tests instead of setting up real Maskinporten credentials.
 */
fun Application.configurePdp(client: PdpClient = pdpClientFrom(environment.config)) {
    dependencies.provide<PdpClient> { client }
}

private fun pdpClientFrom(config: ApplicationConfig): PdpClient {
    val builder = PdpClient.builder()
        .environment(AltinnEnvironment.valueOf(config.property("altinn.environment").getString()))
        .subscriptionKey(config.property("altinn.subscriptionKey").getString())
        .maskinportenClientId(config.property("maskinporten.clientId").getString())
        .maskinportenJwk(config.property("maskinporten.clientJwk").getString())
    config.property("maskinporten.tokenUrl").getString().takeIf { it.isNotBlank() }
        ?.let { builder.maskinportenTokenUrl(it) }
    return builder.build()
}
