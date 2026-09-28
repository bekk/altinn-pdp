package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import no.kartverket.altinnpdp.client.AltinnEnvironment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {

    @Test
    fun `a misspelled environment names the valid values`() {
        val e = assertFailsWith<IllegalStateException> {
            MapApplicationConfig("altinn.environment" to "tt02").enum<AltinnEnvironment>("altinn.environment")
        }

        assertContains(e.message!!, "altinn.environment")
        assertContains(e.message!!, "TT02, PROD")
    }

    @Test
    fun `every value is trimmed the same way`() {
        val config = MapApplicationConfig("key" to "  value  ", "flag" to " true ")

        assertEquals("value", config.required("key"))
        assertEquals(true, config.boolean("flag", default = false))
    }

    @Test
    fun `a flag must be true or false`() {
        val e = assertFailsWith<IllegalStateException> { MapApplicationConfig("flag" to "yes").boolean("flag", default = true) }

        assertContains(e.message!!, "must be true or false")
    }

    @Test
    fun `a blank required value counts as missing`() {
        val e = assertFailsWith<IllegalStateException> { MapApplicationConfig("key" to "   ").required("key") }

        assertContains(e.message!!, "Missing required configuration key")
    }

    @Test
    fun `a port must be a number from 1 to 65535`() {
        for (raw in listOf("http", "0", "65536")) {
            val e = assertFailsWith<IllegalStateException>(raw) {
                MapApplicationConfig("server.port" to raw).port("server.port")
            }

            assertContains(e.message!!, "server.port", message = raw)
        }
    }

    @Test
    fun `loads the application config from the given file`() {
        val file = File.createTempFile("application", ".yaml").apply { deleteOnExit() }
        file.writeText("server:\n  port: \"9090\"\n")

        assertEquals(9090, loadApplicationConfig(file.path).port("server.port"))
    }

    @Test
    fun `refuses an application config path that is not a file`() {
        val e = assertFailsWith<IllegalStateException> { loadApplicationConfig("/no/such/application.yaml") }

        assertContains(e.message!!, "/no/such/application.yaml")
    }

    @Test
    fun `refuses an application config file that is not yaml`() {
        val file = File.createTempFile("application", ".conf").apply { deleteOnExit() }

        val e = assertFailsWith<IllegalStateException> { loadApplicationConfig(file.path) }

        assertContains(e.message!!, ".yaml or .yml")
    }
}
