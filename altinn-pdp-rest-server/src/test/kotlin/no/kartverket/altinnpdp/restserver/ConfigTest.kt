package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import no.kartverket.altinnpdp.client.AltinnEnvironment
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
}
