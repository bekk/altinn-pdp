package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val SPEC_RESOURCE = "documentation.json"

/**
 * Serves the OpenAPI spec as JSON on `/openapi`. No UI is bundled - the spec is meant to be consumed by whatever
 * renders our API documentation. Deliberately avoids Ktor's `openAPI` plugin, which runs swagger-codegen at startup
 * and needs a writable working directory - something we do not want in a container.
 */
fun Application.configureOpenApi() {
    val spec = checkNotNull(javaClass.classLoader.getResourceAsStream(SPEC_RESOURCE)) {
        "Missing $SPEC_RESOURCE on the classpath"
    }.bufferedReader().use { it.readText() }

    routing {
        get("/openapi") {
            call.respondText(spec, ContentType.Application.Json)
        }
    }
}
