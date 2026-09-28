package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import no.kartverket.altinnpdp.client.AltinnEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class ApplicationConfigTest {

    @Test
    fun `the altinn environment default is a real AltinnEnvironment`() {
        val configured = loadConfig().property("altinn.environment").getString()
        assertNotNull(
            AltinnEnvironment.entries.find { it.name == configured },
            "altinn.environment resolved to \"$configured\", which is not an AltinnEnvironment",
        )
    }

    @Test
    fun `the port default is a port number`() {
        loadConfig().port("server.port")
    }

    private fun loadConfig(): ApplicationConfig {
        val required = listOf("ALTINN_SUBSCRIPTION_KEY", "MASKINPORTEN_CLIENT_ID", "MASKINPORTEN_CLIENT_JWK")
        required.forEach { System.setProperty(it, "placeholder") }
        try {
            return YamlConfig("application.yaml") ?: fail("application.yaml is not on the classpath")
        } finally {
            required.forEach { System.clearProperty(it) }
        }
    }
}
