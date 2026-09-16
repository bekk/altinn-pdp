package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.routing.*

fun Application.configureHttp() {
    routing {
        openAPI(path = "openapi", swaggerFile = "documentation.yaml")
    }
}
