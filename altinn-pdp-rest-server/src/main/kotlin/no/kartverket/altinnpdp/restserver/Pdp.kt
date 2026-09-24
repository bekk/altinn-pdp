package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import java.net.http.HttpClient
import java.time.Duration

fun Application.configurePdp(client: PdpClient = pdpClientFromConfig()) {
    install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun Application.pdpClientFromConfig(): PdpClient {
    val config = environment.config
    val timeouts = timeoutsFromConfig(config)
    return PdpClient.builder()
        .environment(config.enum<AltinnEnvironment>("altinn.environment"))
        .subscriptionKey(config.required("altinn.subscriptionKey"))
        .maskinportenClientId(config.required("maskinporten.clientId"))
        .maskinportenJwk(config.required("maskinporten.clientJwk"))
        .httpClient(
            JavaPdpHttpClient(
                HttpClient.newBuilder()
                    .connectTimeout(timeouts.connect)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
                timeouts.request,
            ),
        )
        .build()
}

internal data class Timeouts(val connect: Duration, val request: Duration)

internal fun timeoutsFromConfig(config: ApplicationConfig = MapApplicationConfig()): Timeouts = Timeouts(
    connect = config.millis("timeouts.connectMs", CONNECT),
    request = config.millis("timeouts.requestMs", REQUEST),
)

private const val CONNECT = 2_000L
private const val REQUEST = 3_000L
