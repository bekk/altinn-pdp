package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import no.kartverket.altinnpdp.client.PdpAuthorization
import no.kartverket.altinnpdp.restserver.authorize.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.authorize.cache.AuthorizationWithAge
import no.kartverket.altinnpdp.restserver.authorize.cache.PdpDecisionCache
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun `test health liveness endpoint`() = testServer {
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the generated spec as json`() = testServer {
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("/authorize"), spec.getValue("paths").jsonObject.keys)
        assertFalse(spec.containsKey("servers"), "the host differs per environment, so the spec names none")
    }

    @Test
    fun `the decision cache is closed when the server stops`() {
        var closed = false
        val cache = object : PdpDecisionCache {
            override suspend fun get(
                key: AuthorizeRequest.Parsed,
                maxAge: Duration?,
                load: suspend () -> PdpAuthorization,
            ) = AuthorizationWithAge(load(), Duration.ZERO)

            override fun close() {
                closed = true
            }
        }
        authorizeTest(decisionCache = cache) { postAuthorize() }

        assertTrue(closed)
    }
}
