package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import no.kartverket.altinnpdp.client.AltinnEnvironment

class ApplicationConfigTest {

    @Test
    fun `every module named in application yaml exists`() {
        val modules = loadConfig().property("ktor.application.modules").getList()
        assertTrue(modules.isNotEmpty(), "application.yaml declares no modules")

        for (module in modules) {
            val className = module.substringBeforeLast('.')
            val functionName = module.substringAfterLast('.')
            val loaded = runCatching { Class.forName(className) }.getOrNull()
            val clazz = assertNotNull(loaded, "Module \"$module\": no such class $className")
            assertTrue(
                clazz.methods.any { it.name == functionName },
                "Module \"$module\": $className has no $functionName function",
            )
        }
    }

    @Test
    fun `the altinn environment default is a real AltinnEnvironment`() {
        val configured = loadConfig().property("altinn.environment").getString()
        assertNotNull(
            AltinnEnvironment.entries.find { it.name == configured },
            "altinn.environment resolved to \"$configured\", which is not an AltinnEnvironment",
        )
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
