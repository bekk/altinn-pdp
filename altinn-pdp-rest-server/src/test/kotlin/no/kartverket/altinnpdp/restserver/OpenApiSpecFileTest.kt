package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiSpecFileTest {

    @Test
    fun `the checked-in spec is the one the server serves`() = testApplication {
        application {
            module(testPdpClient())
        }

        val served = client.get("/openapi").bodyAsText() + "\n"
        val file = File("openapi.json")
        if (System.getProperty("openApiSpec.write") == "true") file.writeText(served)

        assertEquals(file.readText(), served, "openapi.json is stale - run ./gradlew generateOpenApiSpec")
    }

    @Test
    fun `every required field exists in its schema`() = testApplication {
        application {
            module(testPdpClient())
        }

        val spec = Json.parseToJsonElement(client.get("/openapi").bodyAsText()).jsonObject
        val schemas = spec.getValue("components").jsonObject.getValue("schemas").jsonObject
        assertTrue(schemas.isNotEmpty(), "expected the spec to have schemas")

        for ((name, schema) in schemas) {
            val required = schema.jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val properties = schema.jsonObject["properties"]?.jsonObject?.keys.orEmpty()

            assertEquals(emptyList(), required - properties, "$name requires fields it does not have")
        }
    }
}
