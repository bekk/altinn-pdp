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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.client.auth.AccessToken
import no.kartverket.altinnpdp.client.auth.AltinnTokenProvider
import no.kartverket.altinnpdp.client.exception.AltinnException
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError

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

    private fun pdpClientAgainst(server: HttpServer, tokenProvider: AltinnTokenProvider): PdpClient =
        PdpClient(
            "http://localhost:${server.address.port}",
            tokenProvider,
            "test-subscription-key",
            timeoutsFromConfig(),
        )

    private fun authorizeTest(
        decision: String,
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

    @Test
    fun `test health liveness endpoint`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `openapi endpoint serves the spec as json from the classpath`() = testApplication {
        application {
            configureOpenApi()
            configureRouting()
        }
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

        val spec = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.0.3", spec.getValue("openapi").jsonPrimitive.content)
        assertTrue(spec.getValue("paths").jsonObject.containsKey("/authorize"))
    }

    @Test
    fun `authorize returns the PDP decision`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                """{"systemuserId":"","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `authorize rejects an customerOrganizationNumber that isn't 9 digits`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"12345","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse(
                error = "Validation failed",
                code = "VALIDATION_ERROR",
                errors = listOf(
                    FieldError("customerOrganizationNumber", "INVALID_FORMAT", "customerOrganizationNumber must be exactly 9 digits"),
                ),
            ),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize reports the missing field when one is absent`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody("""{"resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse(
                error = "Validation failed",
                code = "VALIDATION_ERROR",
                errors = listOf(FieldError("systemuserId", "MISSING", "systemuserId is required")),
            ),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize reports every missing field when several are absent`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody("""{"customerOrganizationNumber":"923609016","action":"read"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            ErrorResponse(
                error = "Validation failed",
                code = "VALIDATION_ERROR",
                errors = listOf(
                    FieldError("systemuserId", "MISSING", "systemuserId is required"),
                    FieldError("resourceId", "MISSING", "resourceId is required"),
                ),
            ),
            Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()),
        )
    }

    @Test
    fun `authorize without a Content-Type header returns 400, not 500`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            setBody(
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `authorize maps a PDP server failure to 502`() = authorizeTest(decision = "Permit", statusCode = 500) {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                    """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                    """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
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
                """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource","customerOrganizationNumber":"923609016","action":"read"}""",
            )
        }

        val body = response.bodyAsText()
        assertFalse(body.contains("minimumAuthenticationLevel"), "expected no level fields in: $body")
    }

    @Test
    fun `every validation error is reported in one response`() = authorizeTest(decision = "Permit") {
        val response = client.post("/authorize") {
            contentType(ContentType.Application.Json)
            setBody("""{"systemuserId":"nope","resourceId":"ab","customerOrganizationNumber":"12345"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
        assertEquals("VALIDATION_ERROR", body.code)
        val errors = body.errors!!
        assertEquals(listOf("systemuserId", "resourceId", "customerOrganizationNumber", "action"), errors.map { it.field })
        assertEquals(listOf("INVALID_FORMAT", "INVALID_FORMAT", "INVALID_FORMAT", "MISSING"), errors.map { it.code })
    }

    @Test
    fun `an customerOrganizationNumber with a bad check digit is rejected before Altinn is called`() =
        authorizeTest(decision = "Permit") {
            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                        """"customerOrganizationNumber":"123456789","action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
            assertEquals("MOD11", body.errors!!.single().message.substringAfterLast("valid ").substringBefore(" "))
        }

    @Test
    fun `a malformed body never echoes the request or kotlinx's own advice back`() =
        authorizeTest(decision = "Permit") {
            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                        """"customerOrganizationNumber":923609016,"action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val text = response.bodyAsText()
            assertEquals(
                ErrorResponse("Malformed request body", "MALFORMED_BODY"),
                Json.decodeFromString(ErrorResponse.serializer(), text),
            )
            assertFalse(text.contains("coerceInputValues"), "leaks kotlinx advice: $text")
            assertFalse(text.contains("JSON input"), "echoes the caller's body: $text")
            assertFalse(text.contains("923609016"), "echoes the caller's values: $text")
        }

    @Test
    fun `an explicit null is reported as a missing field, not as malformed JSON`() =
        authorizeTest(decision = "Permit") {
            val response = client.post("/authorize") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                        """"customerOrganizationNumber":null,"action":"read"}""",
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
            assertEquals("VALIDATION_ERROR", body.code)
            assertEquals(FieldError("customerOrganizationNumber", "MISSING", "customerOrganizationNumber is required"), body.errors!!.single())
        }

    @Test
    fun `only Altinn's own 400 is the caller's fault`() {
        for ((upstream, expected) in mapOf(400 to HttpStatusCode.BadRequest, 401 to HttpStatusCode.BadGateway)) {
            authorizeTest(decision = "Permit", statusCode = upstream) {
                val response = client.post("/authorize") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                            """"customerOrganizationNumber":"923609016","action":"read"}""",
                    )
                }
                assertEquals(expected, response.status, "for upstream $upstream")
            }
        }
    }

    @Test
    fun `our own credential and quota failures are not blamed on the caller`() {
        for (upstream in listOf(401, 403, 429)) {
            authorizeTest(decision = "Permit", statusCode = upstream) {
                val response = client.post("/authorize") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                            """"customerOrganizationNumber":"923609016","action":"read"}""",
                    )
                }
                assertEquals(HttpStatusCode.BadGateway, response.status, "for upstream $upstream")
                assertEquals(
                    "UPSTREAM_ERROR",
                    Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).code,
                )
            }
        }
    }

    @Test
    fun `a 400 while fetching our own token is not blamed on the caller`() {
        val failures = listOf(
            MaskinportenException("Maskinporten responded 400", statusCode = 400, responseBody = """{"error":"invalid_grant"}"""),
            AltinnException("Altinn responded 400 to the token exchange", statusCode = 400),
        )
        for (failure in failures) {
            val failingTokenProvider = object : AltinnTokenProvider {
                override suspend fun getAltinnToken(): AccessToken = throw failure
            }
            authorizeTest(decision = "Permit", tokenProvider = failingTokenProvider) {
                val response = client.post("/authorize") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"systemuserId":"1725580f-70f4-4ace-a748-4f912497a0d7","resourceId":"test-resource",""" +
                            """"customerOrganizationNumber":"923609016","action":"read"}""",
                    )
                }
                assertEquals(HttpStatusCode.BadGateway, response.status, "for ${failure::class.simpleName}")
                assertEquals(
                    "UPSTREAM_ERROR",
                    Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).code,
                )
            }
        }
    }

    companion object {
        private const val OK_STATUS = "urn:oasis:names:tc:xacml:1.0:status:ok"
    }
}
