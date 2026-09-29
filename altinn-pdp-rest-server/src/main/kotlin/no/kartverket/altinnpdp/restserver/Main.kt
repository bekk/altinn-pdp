package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse

fun main() {
    val config = loadApplicationConfig()
    logbackConfigFile(config)?.let { System.setProperty("logback.configurationFile", it) }
    embeddedServer(
        Netty,
        environment = applicationEnvironment { this.config = config },
        configure = { connector { port = config.port("server.port") } },
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

@OptIn(ExperimentalKtorApi::class)
fun Application.configureRouting() {
    val spec: String by lazy { openApiSpec() }

    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }.describe(healthLiveOperation)

        get("/openapi") {
            call.respondText(spec, ContentType.Application.Json)
        }.hide()

        post("/authorize") {
            val pdpClient: PdpClient by dependencies
            val request = call.receive<AuthorizeRequest>()
            val authorization = pdpClient.authorize(
                subject = request.subject,
                resourceId = request.resourceId,
                organizationNumber = request.organizationNumber,
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
        }.describe(authorizeOperation)
    }
}
