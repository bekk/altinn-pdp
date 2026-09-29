package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.fail

class ApplicationConfigTest {

    @Test
    fun `the altinn environment has no default`() {
        assertFails { loadConfig(without = "ALTINN_ENVIRONMENT") }
    }

    @Test
    fun `the port default is a port number`() {
        loadConfig().port("server.port")
    }

    private fun loadConfig(without: String? = null): ApplicationConfig {
        val required = mapOf(
            "ALTINN_ENVIRONMENT" to "TT02",
            "ALTINN_SUBSCRIPTION_KEY" to "placeholder",
            "MASKINPORTEN_CLIENT_ID" to "placeholder",
            "MASKINPORTEN_CLIENT_JWK" to "placeholder",
        ).filterKeys { it != without }
        required.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            return YamlConfig("application.yaml") ?: fail("application.yaml is not on the classpath")
        } finally {
            required.keys.forEach { System.clearProperty(it) }
        }
    }
}
