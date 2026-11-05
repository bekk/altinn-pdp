package no.kartverket.altinnpdp.restserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.JavaOutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.restserver.authorize.authorize
import no.kartverket.altinnpdp.restserver.authorize.cache.NoPdpDecisionCache
import no.kartverket.altinnpdp.restserver.authorize.cache.PdpDecisionCache
import no.kartverket.altinnpdp.restserver.authorize.configurePidValidation
import no.kartverket.altinnpdp.restserver.config.AppConfig
import no.kartverket.altinnpdp.restserver.config.file
import no.kartverket.altinnpdp.restserver.config.loadApplicationConfig
import no.kartverket.altinnpdp.restserver.observability.LoggingOutgoingHttpClient
import no.kartverket.altinnpdp.restserver.observability.installCallLogging
import no.kartverket.altinnpdp.restserver.problem.errorResponses
import java.net.http.HttpClient
import java.time.Duration

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
    installCallLogging(accessLog)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) { errorResponses() }
    monitor.subscribe(ApplicationStopped) { decisionCache.close() }

    val spec by lazy { openApiSpec() }

    routing {
        get("/health/live") { call.respond(HttpStatusCode.OK) }.hide()
        get("/openapi") { call.respondText(spec, ContentType.Application.Json) }.hide()
        authorize(pdpClient, decisionCache)
    }
}

private fun pdpClient(config: AppConfig): PdpClient = PdpClient(
    environment = config.altinnEnvironment,
    subscriptionKey = config.altinnSubscriptionKey,
    maskinportenClientId = config.maskinportenClientId,
    maskinportenKey = config.maskinportenKey,
    httpClient = httpClient(),
)

private fun httpClient(): OutgoingHttpClient = LoggingOutgoingHttpClient(
    JavaOutgoingHttpClient(
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        REQUEST_TIMEOUT,
    ),
)

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
