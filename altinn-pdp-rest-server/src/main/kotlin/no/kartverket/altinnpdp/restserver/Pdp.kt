package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import java.net.http.HttpClient
import java.time.Duration

fun Application.configurePdp(client: PdpClient = pdpClientFromConfig()) {
    install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun Application.pdpClientFromConfig(): PdpClient {
    val config = environment.config
    return PdpClient(
        environment = config.enum<AltinnEnvironment>("altinn.environment"),
        subscriptionKey = config.required("altinn.subscriptionKey"),
        maskinportenClientId = config.required("maskinporten.clientId"),
        maskinportenKey = MaskinportenKey.parse(config.required("maskinporten.clientJwk")),
        httpClient = JavaPdpHttpClient(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            REQUEST_TIMEOUT,
        ),
    )
}

internal val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
internal val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
