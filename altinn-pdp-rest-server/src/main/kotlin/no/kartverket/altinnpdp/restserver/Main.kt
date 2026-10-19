package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import org.slf4j.LoggerFactory

fun main() {
    val config = AppConfig.load()
    config.logbackConfigFile?.let { System.setProperty("logback.configurationFile", it) }
    configurePidValidation(config.altinnEnvironment)

    embeddedServer(Netty, port = config.port) {
        module(pdpClient = pdpClient(config), accessLog = config.accessLog, decisionCache = config.decisionCache)
    }.start(wait = true)
}

@OptIn(ExperimentalKtorApi::class)
fun Application.module(
    pdpClient: PdpClient,
    accessLog: Boolean = true,
    decisionCache: PdpDecisionCache = NoPdpDecisionCache,
) {
    if (accessLog) install(CallLogging) { logger = LoggerFactory.getLogger("access") }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) { errorResponses() }

    val spec by lazy { openApiSpec() }

    routing {
        get("/health/live") { call.respond(HttpStatusCode.OK) }.hide()
        get("/openapi") { call.respondText(spec, ContentType.Application.Json) }.hide()
        post("/authorize") {
            val request = call.receive<AuthorizeRequest>().parse()
            val authorization = decisionCache.get(request) {
                pdpClient.authorize(
                    subject = request.subject,
                    resourceId = request.resourceId,
                    customerOrganizationNumber = request.customerOrganizationNumber,
                    action = request.action,
                )
            }

            call.respond(
                AuthorizeResponse(
                    permit = authorization.isPermit,
                    decision = authorization.decision,
                    status = authorization.statusCode,
                    minimumAuthenticationLevel = authorization.minimumAuthenticationLevel,
                ),
            )
        }.describe(authorizeOperation)
    }
}
