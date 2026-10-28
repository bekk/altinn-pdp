package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
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
import java.util.UUID

fun main() {
    val applicationConfig = loadApplicationConfig()
    // Logback reads this when the first logger is created, so it is set before AppConfig builds anything that logs.
    applicationConfig.file("logback.configFile")?.let { System.setProperty("logback.configurationFile", it) }
    val config = AppConfig.load(applicationConfig)
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
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify(::isSafeRequestId)
    }
    install(CallLogging) {
        mdc("workload") { workloadFrom(it.request.headers[CLIENT_CERTIFICATE_HEADER]) }
        callIdMdc("request_id")

        logger = LoggerFactory.getLogger("access")
        disableDefaultColors()
        filter { accessLog }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) { errorResponses() }
    monitor.subscribe(ApplicationStopped) { decisionCache.close() }

    val spec by lazy { openApiSpec() }

    routing {
        get("/health/live") { call.respond(HttpStatusCode.OK) }.hide()
        get("/openapi") { call.respondText(spec, ContentType.Application.Json) }.hide()
        post("/authorize") {
            val request = call.receive<AuthorizeRequest>().parse()
            val maxAge = call.request.headers.maxAge()
            val (authorization, age) = decisionCache.get(request, maxAge) {
                pdpClient.authorize(
                    subject = request.subject,
                    resourceId = request.resourceId,
                    customerOrganizationNumber = request.customerOrganizationNumber,
                    action = request.action,
                )
            }

            call.response.header(HttpHeaders.Age, age.seconds)
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
