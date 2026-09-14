package no.kartverket.altinnpdp.restserver

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    embeddedServer(Netty, port = 8080) {
        configureSerialization()
        configureErrorHandling()
        configurePdp()
        configureHttp()
        configureRouting()
    }.start(wait = true)
}
