package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.client.support.response
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import java.time.Instant
import java.util.Base64

internal const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"

internal fun authorizeBody(
    systemuserId: String? = SAMPLE_SYSTEMUSER_ID,
    pid: String? = null,
    resourceId: String? = "test-resource",
    customerOrganizationNumber: String? = "923609016",
    action: String? = "read",
): String = listOf(
    "systemuserId" to systemuserId,
    "pid" to pid,
    "resourceId" to resourceId,
    "customerOrganizationNumber" to customerOrganizationNumber,
    "action" to action,
).mapNotNull { (field, value) -> value?.let { """"$field":"$it"""" } }
    .joinToString(prefix = "{", postfix = "}")

internal suspend fun ApplicationTestBuilder.postAuthorize(
    body: String = authorizeBody(),
    json: Boolean = true,
    cacheControl: String? = null,
): HttpResponse = client.post("/authorize") {
    if (json) contentType(ContentType.Application.Json)
    cacheControl?.let { header(HttpHeaders.CacheControl, it) }
    setBody(body)
}

internal suspend fun HttpResponse.authorizeResponse(): AuthorizeResponse =
    Json.decodeFromString(AuthorizeResponse.serializer(), bodyAsText())

internal fun authorizeTest(
    altinnEnvironment: AltinnEnvironment = AltinnEnvironment.TT02,
    decision: String = "Permit",
    statusCode: Int = 200,
    obligations: Boolean = false,
    maskinportenStatus: Int = 200,
    exchangeStatus: Int = 200,
    failure: Exception? = null,
    sent: MutableList<OutgoingRequest> = mutableListOf(),
    decisionCache: PdpDecisionCache = NoPdpDecisionCache,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    val altinn = OutgoingHttpClient { request ->
        sent += request
        if (failure != null) throw failure
        when (request.url.path) {
            "/token" -> response(maskinportenStatus, "application/json", """{"access_token":"mp-token","expires_in":3600}""")
            "/authentication/api/v1/exchange/maskinporten" -> response(exchangeStatus, "text/plain", altinnToken())
            "/authorization/api/v1/authorize" -> response(statusCode, "application/json", pdpBody(decision, obligations))
            else -> error("unexpected call to ${request.url}")
        }
    }
    testServer(altinn, altinnEnvironment, decisionCache, block)
}

private fun altinnToken(): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val claims = encoder.encodeToString("""{"exp":${Instant.now().plusSeconds(300).epochSecond}}""".toByteArray())
    return "$header.$claims."
}

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
