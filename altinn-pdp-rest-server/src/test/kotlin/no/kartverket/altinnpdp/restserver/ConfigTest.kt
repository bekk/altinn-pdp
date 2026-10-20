package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.MapApplicationConfig
import no.kartverket.altinnpdp.client.AltinnEnvironment
import java.io.File
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

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
    fun `seconds must be a whole number, 0 or more, and default when unset`() {
        assertEquals(Duration.ofSeconds(30), MapApplicationConfig("key" to "30").seconds("key", default = Duration.ZERO))
        assertEquals(Duration.ZERO, MapApplicationConfig("key" to "0").seconds("key", default = Duration.ofMinutes(1)))
        assertEquals(Duration.ofMinutes(1), MapApplicationConfig().seconds("key", default = Duration.ofMinutes(1)))
        for (raw in listOf("-1", "1.5", "1m")) {
            val e = assertFailsWith<IllegalStateException>(raw) { MapApplicationConfig("key" to raw).seconds("key", default = Duration.ZERO) }
            assertContains(e.message!!, "must be a whole number of seconds", message = raw)
        }
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
    fun `a URL must be http or https and have a host`() {
        for (raw in listOf("localhost:8080", "ftp://digdir.test", "https://", "not a url")) {
            val e = assertFailsWith<IllegalStateException>(raw) {
                MapApplicationConfig("externalFiltering.url" to raw).url("externalFiltering.url")
            }

            assertContains(e.message!!, "http or https URL", message = raw)
        }
    }

    @Test
    fun `a URL must not have a query or fragment`() {
        for (raw in listOf("https://digdir.test?x=1", "https://digdir.test#top")) {
            val e = assertFailsWith<IllegalStateException>(raw) {
                MapApplicationConfig("externalFiltering.url" to raw).url("externalFiltering.url")
            }

            assertContains(e.message!!, "query or fragment", message = raw)
        }
    }

    @Test
    fun `an http or https URL is accepted as it is`() {
        for (raw in listOf("http://localhost:8080", "https://api.samarbeid.digdir.no", "HTTPS://api.samarbeid.digdir.no")) {
            assertEquals(URI(raw), MapApplicationConfig("externalFiltering.url" to raw).url("externalFiltering.url"))
        }
    }

    @Test
    fun `the access log is on unless switched off`() {
        val required = arrayOf(
            "server.port" to "8080",
            "altinn.environment" to "TT02",
            "altinn.subscriptionKey" to "key",
            "maskinporten.clientId" to "client",
            "maskinporten.clientJwk" to testJwk,
        )

        assertEquals(true, AppConfig.load(MapApplicationConfig(*required)).accessLog)
        assertEquals(false, AppConfig.load(MapApplicationConfig(*required, "accessLog.enabled" to "false")).accessLog)
    }

    @Test
    fun `refuses a logback config path that is not a file`() {
        val e = assertFailsWith<IllegalStateException> {
            MapApplicationConfig("logback.configFile" to "/no/such/logback.xml").file("logback.configFile")
        }

        assertContains(e.message!!, "/no/such/logback.xml")
    }

    @Test
    fun `a number falls back to its default when absent`() {
        assertEquals(60, MapApplicationConfig().long("seconds", default = 60, minimum = 1))
        assertEquals(30, MapApplicationConfig("seconds" to "30").long("seconds", default = 60, minimum = 1))
    }

    @Test
    fun `a number must be a whole number of at least the minimum`() {
        assertEquals(0, MapApplicationConfig("seconds" to "0").long("seconds", default = 60, minimum = 0))
        for (raw in listOf("ten", "-1", "1.5")) {
            val e = assertFailsWith<IllegalStateException>(raw) {
                MapApplicationConfig("seconds" to raw).long("seconds", default = 60, minimum = 0)
            }

            assertContains(e.message!!, "seconds must be a whole number of at least 0", message = raw)
        }
    }

    @Test
    fun `the decision cache is off unless enabled`() {
        assertSame(NoPdpDecisionCache, MapApplicationConfig().decisionCache())
        assertSame(NoPdpDecisionCache, MapApplicationConfig("decisionCache.enabled" to "false").decisionCache())
        assertIs<InMemoryPdpDecisionCache>(MapApplicationConfig("decisionCache.enabled" to "true").decisionCache())
    }

    @Test
    fun `the decision cache refuses invalid settings even when disabled`() {
        for (path in listOf("decisionCache.timeToLiveSeconds", "decisionCache.maximumSize")) {
            val config = MapApplicationConfig("decisionCache.enabled" to "false", path to "0")

            val e = assertFailsWith<IllegalStateException>(path) { config.decisionCache() }

            assertContains(e.message!!, path, message = path)
        }
    }

    @Test
    fun `an enabled decision cache refuses a negative default max age`() {
        val config = MapApplicationConfig("decisionCache.enabled" to "true", "decisionCache.defaultMaxAgeSeconds" to "-1")

        val e = assertFailsWith<IllegalStateException> { config.decisionCache() }

        assertContains(e.message!!, "decisionCache.defaultMaxAgeSeconds")
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
