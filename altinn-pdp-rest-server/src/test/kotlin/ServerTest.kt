package no.bekk.altinnpdp.restserver

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.Json
import no.bekk.altinnpdp.auth.AccessToken
import no.bekk.altinnpdp.auth.AltinnTokenProvider
import no.bekk.altinnpdp.client.PdpClient

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

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureHttp()
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `authorize returns the PDP decision`() = testApplication {
        val server = stubPdpServer(decision = "Permit")
        try {
            application {
                configureSerialization()
                configureErrorHandling()
                configurePdp(pdpClientAgainst(server))
                configureRouting()
            }

            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AuthorizeResponse("PERMIT"),
                Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `authorize rejects a blank field with 400`() = testApplication {
        val server = stubPdpServer(decision = "Permit")
        try {
            application {
                configureSerialization()
                configureErrorHandling()
                configurePdp(pdpClientAgainst(server))
                configureRouting()
            }

            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `authorize without a Content-Type header returns 400, not 500`() = testApplication {
        val server = stubPdpServer(decision = "Permit")
        try {
            application {
                configureSerialization()
                configureErrorHandling()
                configurePdp(pdpClientAgainst(server))
                configureRouting()
            }

            // No contentType(...) call - ContentNegotiation then finds no converter for the
            // request at all and throws CannotTransformContentToTypeException, a different
            // exception type than a malformed JSON body would (JsonConvertException).
            val response = client.post("/authorize") {
                setBody(
                    """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `authorize maps a PDP failure to 502`() = testApplication {
        val server = stubPdpServer(decision = "Permit", statusCode = 500)
        try {
            application {
                configureSerialization()
                configureErrorHandling()
                configurePdp(pdpClientAgainst(server))
                configureRouting()
            }

            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadGateway, response.status)
        } finally {
            server.stop(0)
        }
    }
}
