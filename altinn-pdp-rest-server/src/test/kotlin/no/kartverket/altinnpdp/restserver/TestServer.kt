package no.kartverket.altinnpdp.restserver

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.support.testMaskinportenKey
import no.kartverket.altinnpdp.restserver.models.ErrorResponse

internal fun testServer(
    altinn: OutgoingHttpClient = unexpectedCall,
    altinnEnvironment: AltinnEnvironment = AltinnEnvironment.TT02,
    decisionCache: PdpDecisionCache = NoPdpDecisionCache,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    configurePidValidation(altinnEnvironment)
    application { module(testPdpClient(altinnEnvironment, altinn), decisionCache = decisionCache) }
    block()
}

internal fun testPdpClient(
    altinnEnvironment: AltinnEnvironment = AltinnEnvironment.TT02,
    httpClient: OutgoingHttpClient = unexpectedCall,
): PdpClient = PdpClient(
    environment = altinnEnvironment,
    subscriptionKey = "test-subscription-key",
    maskinportenClientId = "test-client",
    maskinportenKey = testMaskinportenKey,
    httpClient = httpClient,
)

private val unexpectedCall = OutgoingHttpClient { error("unexpected call to ${it.url}") }

internal suspend fun HttpResponse.errorResponse(): ErrorResponse =
    Json.decodeFromString(ErrorResponse.serializer(), bodyAsText())
