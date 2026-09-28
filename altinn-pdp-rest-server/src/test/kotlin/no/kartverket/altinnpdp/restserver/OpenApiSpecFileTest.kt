package no.kartverket.altinnpdp.restserver

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenApiSpecFileTest {

    @Test
    fun `the checked-in spec is the one the server serves`() = testApplication {
        application {
            configureRouting()
        }

        val served = OpenApiSpecFile.contentsFor(client.get("/openapi").bodyAsText())

        assertEquals(
            File(OpenApiSpecFile.NAME).readText(),
            served,
            "${OpenApiSpecFile.NAME} is stale - run ./gradlew generateOpenApiSpec",
        )
    }
}
