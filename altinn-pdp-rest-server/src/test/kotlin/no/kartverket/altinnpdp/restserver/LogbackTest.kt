package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class LogbackTest {

    @Test
    fun `refuses a path that is not a file`() {
        val e = assertFailsWith<IllegalStateException> {
            logbackConfigFile(MapApplicationConfig("logback.configFile" to "/no/such/logback.xml"))
        }

        assertContains(e.message!!, "/no/such/logback.xml")
    }
}
