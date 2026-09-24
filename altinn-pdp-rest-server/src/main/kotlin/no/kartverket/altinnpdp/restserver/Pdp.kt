package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.Timeouts
import java.time.Duration

fun Application.configurePdp(client: PdpClient = pdpClientFromConfig()) {
    install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun Application.pdpClientFromConfig(): PdpClient {
    val config = environment.config
    return PdpClient.builder()
        .environment(config.enum<AltinnEnvironment>("altinn.environment"))
        .subscriptionKey(config.required("altinn.subscriptionKey"))
        .maskinportenClientId(config.required("maskinporten.clientId"))
        .maskinportenJwk(config.required("maskinporten.clientJwk"))
        .timeouts(timeoutsFromConfig(config))
        .build()
}

internal fun timeoutsFromConfig(config: ApplicationConfig = MapApplicationConfig()): Timeouts = Timeouts(
    request = config.millis("timeouts.requestMs", REQUEST),
    total = config.millis("timeouts.totalMs", TOTAL),
)

private const val REQUEST = 4_000L
private const val TOTAL = 8_000L
