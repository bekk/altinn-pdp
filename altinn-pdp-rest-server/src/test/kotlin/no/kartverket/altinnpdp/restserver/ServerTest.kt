package no.kartverket.altinnpdp.restserver

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.PdpClient

class ServerTest {

    private val fakeTokenProvider = object : AltinnTokenProvider {
        override suspend fun getAltinnToken() = AccessToken("fake-token", Instant.now().plusSeconds(60))
    }

    private fun stubPdpServer(decision: String, statusCode: Int = 200): HttpServer {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext(PdpClient.AUTHORIZE_PATH) { exchange ->
            val body = """{"Response":[{"Decision":"$decision"}]}""".toByteArray()
            exchange.sendResponseHeaders(statusCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun pdpClientAgainst(server: HttpServer): PdpClient =
        PdpClient("http://localhost:${server.address.port}", fakeTokenProvider, "test-subscription-key")

    /**
     * Wraps [testApplication] with a stubbed PDP backend wired into [configurePdp] - the setup
     * every `/authorize` test needs. [decision] and [statusCode] configure the stub; [block] is
     * the actual test body, run once the application and stub are ready.
     */
    private fun authorizeTest(
        decision: String,
        statusCode: Int = 200,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val server = stubPdpServer(decision, statusCode)
        try {
            application {
                configureSerialization()
                configureErrorHandling()
                configurePdp(pdpClientAgainst(server))
                configureRouting()
            }
            block()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `test health liveness endpoint`() = testApplication {
        application {
            configureHttp()
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `authorize returns the PDP decision`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            AuthorizeResponse(permit = true, decision = "PERMIT"),
            Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize returns a Deny decision`() = authorizeTest(decision = "Deny") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            AuthorizeResponse(permit = false, decision = "DENY"),
            Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize returns a NotApplicable decision`() = authorizeTest(decision = "NotApplicable") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            AuthorizeResponse(permit = false, decision = "NOT_APPLICABLE"),
            Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize returns an Indeterminate decision`() = authorizeTest(decision = "Indeterminate") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            AuthorizeResponse(permit = false, decision = "INDETERMINATE"),
            Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize rejects a blank field with 400`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `authorize rejects an organizationNumber that isn't 9 digits`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"12345","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse("organizationNumber must be exactly 9 digits"),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize reports the missing field when one is absent`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"res-1","organizationNumber":"923609016","action":"read"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse("Missing required field: systemuserId"),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize reports every missing field when several are absent`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody("""{"organizationNumber":"923609016","action":"read"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse("Missing required fields: systemuserId, resourceId"),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize without a Content-Type header returns 400, not 500`() = authorizeTest(decision = "Permit") {
        // No contentType(...) call - ContentNegotiation then finds no converter for the
        // request at all and throws CannotTransformContentToTypeException, a different
        // exception type than a malformed JSON body would (JsonConvertException).
        val response = client.post("/authorize") {
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `authorize maps a PDP server failure to 502`() = authorizeTest(decision = "Permit", statusCode = 500) {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `authorize maps a PDP rejection of the request to 400, not 502`() =
        authorizeTest(decision = "Permit", statusCode = 400) {
            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
