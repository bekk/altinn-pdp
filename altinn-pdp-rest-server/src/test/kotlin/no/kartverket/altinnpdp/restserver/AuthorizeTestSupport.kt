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
import no.kartverket.altinnpdp.client.support.FakeAltinn
import no.kartverket.altinnpdp.client.support.SAMPLE_SYSTEMUSER_ID
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse

internal const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"

internal fun authorizeBody(
    systemuserId: String? = SAMPLE_SYSTEMUSER_ID,
    pid: String? = null,
    resourceId: String? = "test-resource",
    customerOrganizationNumber: String? = "923609016",
    action: String? = "read",
    resourceAttributesJson: String? = null,
): String = (
    listOf(
        "systemuserId" to systemuserId,
        "pid" to pid,
        "resourceId" to resourceId,
        "customerOrganizationNumber" to customerOrganizationNumber,
        "action" to action,
    ).mapNotNull { (field, value) -> value?.let { """"$field":"$it"""" } } +
        listOfNotNull(resourceAttributesJson?.let { """"resourceAttributes":$it""" })
    ).joinToString(prefix = "{", postfix = "}")

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
    altinn: FakeAltinn = FakeAltinn(),
    decisionCache: PdpDecisionCache = NoPdpDecisionCache,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testServer(altinn, altinnEnvironment, decisionCache, block)
