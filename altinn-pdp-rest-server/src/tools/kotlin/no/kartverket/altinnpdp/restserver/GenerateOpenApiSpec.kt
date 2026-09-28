package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

fun main() {
    val server = embeddedServer(Netty, port = 0, module = Application::configureRouting).start()
    try {
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/openapi")).build()
        val spec = HttpClient.newHttpClient().send(request, BodyHandlers.ofString()).body()

        val file = File(OpenApiSpecFile.NAME)
        file.writeText(OpenApiSpecFile.contentsFor(spec))
        println("Wrote ${file.absolutePath}")
    } finally {
        server.stop()
    }
}
