package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val SPEC_RESOURCE = "documentation.yaml"

/**
 * Serves the OpenAPI spec on `/openapi` and a Swagger UI for it on `/swagger`. Deliberately avoids Ktor's `openAPI`
 * plugin, which runs swagger-codegen at startup and needs a writable working directory - something we do not want in
 * a container. `swaggerUI` only serves the spec plus the Swagger UI assets, and writes nothing.
 */
fun Application.configureOpenApi() {
    val spec = checkNotNull(javaClass.classLoader.getResourceAsStream(SPEC_RESOURCE)) {
        "Missing $SPEC_RESOURCE on the classpath"
    }.bufferedReader().use { it.readText() }

    routing {
        get("/openapi") {
            call.respondText(spec, ContentType("application", "yaml"))
        }
        swaggerUI(path = "swagger", swaggerFile = SPEC_RESOURCE)
    }
}
