package no.kartverket.altinnpdp.restserver

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.auth.MaskinportenKey
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpResponse
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import java.time.Instant
import java.util.Base64

internal const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"

internal const val SAMPLE_SYSTEMUSER_ID = "1725580f-70f4-4ace-a748-4f912497a0d7"

private val testKey: MaskinportenKey =
    MaskinportenKey.parse(RSAKeyGenerator(2048).keyID("test-key").generate().toJSONString())

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

/** Runs [block] against the routes, with Maskinporten, the token exchange and the PDP faked behind them. */
internal fun authorizeTest(
    decision: String = "Permit",
    statusCode: Int = 200,
    obligations: Boolean = false,
    maskinportenStatus: Int = 200,
    exchangeStatus: Int = 200,
    failure: Exception? = null,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    val altinn = PdpHttpClient { request ->
        if (failure != null) throw failure
        when (request.url.path) {
            "/token" -> PdpHttpResponse(maskinportenStatus, """{"access_token":"mp-token","expires_in":3600}""")
            "/authentication/api/v1/exchange/maskinporten" -> PdpHttpResponse(exchangeStatus, altinnToken())
            "/authorization/api/v1/authorize" -> PdpHttpResponse(statusCode, pdpBody(decision, obligations))
            else -> error("unexpected call to ${request.url}")
        }
    }
    application {
        configureSerialization()
        configureErrorHandling()
        configurePdp(
            PdpClient(
                environment = AltinnEnvironment.TT02,
                subscriptionKey = "test-subscription-key",
                maskinportenClientId = "test-client",
                maskinportenKey = testKey,
                httpClient = altinn,
            ),
        )
        configureRouting()
    }
    block()
}

/** An unsigned JWT with only an expiry, which is all the client reads from the exchanged token. */
private fun altinnToken(): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val claims = encoder.encodeToString("""{"exp":${Instant.now().plusSeconds(300).epochSecond}}""".toByteArray())
    return "$header.$claims."
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
