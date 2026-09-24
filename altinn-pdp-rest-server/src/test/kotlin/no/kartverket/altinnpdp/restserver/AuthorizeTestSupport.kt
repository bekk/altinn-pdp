package no.kartverket.altinnpdp.restserver

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.auth.AltinnToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import java.net.InetSocketAddress
import java.time.Instant

internal const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"

internal const val SAMPLE_SYSTEMUSER_ID = "1725580f-70f4-4ace-a748-4f912497a0d7"

internal val fakeTokenProvider = object : AltinnTokenProvider {
    override suspend fun getAltinnToken() = AltinnToken("fake-token", Instant.now().plusSeconds(60))
}

internal fun failingTokenProvider(failure: Throwable) = object : AltinnTokenProvider {
    override suspend fun getAltinnToken(): AltinnToken = throw failure
}

/** The body every test starts from; a `null` leaves the field out of the JSON entirely. */
internal fun authorizeBody(
    systemuserId: String? = SAMPLE_SYSTEMUSER_ID,
    resourceId: String? = "test-resource",
    customerOrganizationNumber: String? = "923609016",
    action: String? = "read",
): String = listOf(
    "systemuserId" to systemuserId,
    "resourceId" to resourceId,
    "customerOrganizationNumber" to customerOrganizationNumber,
    "action" to action,
).mapNotNull { (field, value) -> value?.let { """"$field":"$it"""" } }
    .joinToString(prefix = "{", postfix = "}")

internal suspend fun ApplicationTestBuilder.postAuthorize(
    body: String = authorizeBody(),
    json: Boolean = true,
): HttpResponse = client.post("/authorize") {
    if (json) contentType(ContentType.Application.Json)
    setBody(body)
}

internal suspend fun HttpResponse.authorizeResponse(): AuthorizeResponse =
    Json.decodeFromString(AuthorizeResponse.serializer(), bodyAsText())

internal suspend fun HttpResponse.errorResponse(): ErrorResponse =
    Json.decodeFromString(ErrorResponse.serializer(), bodyAsText())

/** Runs [block] against the routes, with a stubbed Altinn PDP behind them. */
internal fun authorizeTest(
    decision: String = "Permit",
    statusCode: Int = 200,
    obligations: Boolean = false,
    tokenProvider: AltinnTokenProvider = fakeTokenProvider,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    val server = stubPdpServer(decision, statusCode, obligations)
    try {
        application {
            configureSerialization()
            configureErrorHandling()
            configurePdp(pdpClientAgainst(server, tokenProvider))
            configureRouting()
        }
        block()
    } finally {
        server.stop(0)
    }
}

private fun stubPdpServer(decision: String, statusCode: Int, obligations: Boolean): HttpServer {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext(PdpClient.AUTHORIZE_PATH) { exchange ->
        val body = pdpBody(decision, obligations).toByteArray()
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    return server
}

// Copied from a real TT02 answer.
private fun pdpBody(decision: String, obligations: Boolean): String {
    val obligationsJson = if (obligations) {
        """[{"id":"urn:altinn:obligation:authenticationLevel1","attributeAssignment":[
           {"attributeId":"urn:altinn:obligation1-assignment1","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel"}]},
           {"id":"urn:altinn:obligation:authenticationLevel2","attributeAssignment":[
           {"attributeId":"urn:altinn:obligation2-assignment2","value":"3",
            "category":"urn:altinn:minimum-authenticationlevel-org"}]}]"""
            .trimIndent().replace("\n", "").replace(" ", "")
    } else {
        "null"
    }
    return """{"response":[{"decision":"$decision","status":{"statusMessage":null,"statusDetails":null,
        "statusCode":{"value":"$OK_STATUS","statusCode":null}},"obligations":$obligationsJson,
        "associateAdvice":null,"category":null,"policyIdentifierList":null}]}"""
        .trimIndent().replace("\n", "")
}

private fun pdpClientAgainst(server: HttpServer, tokenProvider: AltinnTokenProvider): PdpClient =
    PdpClient(
        "http://localhost:${server.address.port}",
        tokenProvider,
        "test-subscription-key",
        timeoutsFromConfig(),
    )
