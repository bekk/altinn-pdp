package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.yaml.YamlConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse

fun main() {
    embeddedServer(
        Netty,
        environment = applicationEnvironment {
            config = YamlConfig("application.yaml") ?: error("application.yaml is not on the classpath")
        },
        configure = { connector { port = 8080 } },
        module = Application::module,
    ).start(wait = true)
}

fun Application.module() {
    configureAccessLogging()
    configureSerialization()
    configureErrorHandling()
    configurePdp()
    configureRouting()
}

fun Application.configureRouting() {
    val openApiSpec = readOpenApiSpec()

    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }

        get("/openapi") {
            call.respondText(openApiSpec, ContentType.Application.Json)
        }

        post("/authorize") {
            val pdpClient: PdpClient by dependencies
            val request = call.receive<AuthorizeRequest>().validated()
            val authorization = pdpClient.authorize(
                systemuserId = request.systemuserId,
                resourceId = request.resourceId,
                customerOrganizationNumber = request.customerOrganizationNumber,
                action = request.action,
            )

            call.respond(
                AuthorizeResponse(
                    permit = authorization.isPermit,
                    decision = authorization.decision,
                    status = authorization.statusCode,
                    minimumAuthenticationLevel = authorization.minimumAuthenticationLevel,
                    minimumAuthenticationLevelOrg = authorization.minimumAuthenticationLevelOrg,
                ),
            )
        }
    }
}

// Read from the classpath rather than through Ktor's openAPI plugin, which needs a writable working directory.
private fun Application.readOpenApiSpec(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream("openapi.json")) { "openapi.json is not on the classpath" }
        .bufferedReader()
        .use { it.readText() }
