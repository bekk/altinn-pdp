package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ConfigLoader.load()
    embeddedServer(
        Netty,
        environment = applicationEnvironment { this.config = config },
        configure = { connector { port = config.property("ktor.deployment.port").getString().toInt() } },
    ) {
        configureSerialization()
        configureErrorHandling()
        configurePdp()
        configureHttp()
        configureRouting()
    }.start(wait = true)
}
