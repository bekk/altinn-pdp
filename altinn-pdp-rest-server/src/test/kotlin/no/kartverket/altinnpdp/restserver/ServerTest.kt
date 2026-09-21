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

    private fun stubPdpServer(
        decision: String,
        statusCode: Int = 200,
        obligations: Boolean = false,
    ): HttpServer {
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

    private fun pdpClientAgainst(server: HttpServer): PdpClient =
        PdpClient(
            "http://localhost:${server.address.port}",
            fakeTokenProvider,
            "test-subscription-key",
            timeoutsFromConfig(),
        )

    private fun authorizeTest(
        decision: String,
        statusCode: Int = 200,
        obligations: Boolean = false,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val server = stubPdpServer(decision, statusCode, obligations)
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
            configureOpenApi()
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the spec from the classpath`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().startsWith("openapi:"))
    }

    @Test
    fun `swagger ui is served`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        val response = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("swagger-ui"))
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
            AuthorizeResponse(permit = true, decision = "PERMIT", status = OK_STATUS),
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
            AuthorizeResponse(permit = false, decision = "DENY", status = OK_STATUS),
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
            AuthorizeResponse(permit = false, decision = "NOT_APPLICABLE", status = OK_STATUS),
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
            AuthorizeResponse(permit = false, decision = "INDETERMINATE", status = OK_STATUS),
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

    @Test
    fun `authorize passes the minimum authentication levels through to the caller`() =
        authorizeTest(decision = "Permit", obligations = true) {
            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AuthorizeResponse(
                    permit = true,
                    decision = "PERMIT",
                    status = OK_STATUS,
                    minimumAuthenticationLevel = 3,
                    minimumAuthenticationLevelOrg = 3,
                ),
                Json.decodeFromString(AuthorizeResponse.serializer(), response.bodyAsText()),
            )
        }

    @Test
    fun `the added fields are omitted when Altinn sends nothing for them`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"su-1","resourceId":"res-1","organizationNumber":"923609016","action":"read"}""",
            )
        }

        val body = response.bodyAsText()
        assertFalse(body.contains("minimumAuthenticationLevel"), "expected no level fields in: $body")
    }

    companion object {
        private const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"
    }
}
