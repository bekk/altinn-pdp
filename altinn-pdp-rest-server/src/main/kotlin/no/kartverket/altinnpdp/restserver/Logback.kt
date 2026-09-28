package no.kartverket.altinnpdp.restserver

import io.ktor.server.config.ApplicationConfig
import java.io.File

internal fun logbackConfigFile(config: ApplicationConfig): String? {
    val path = config.optional("logback.configFile") ?: return null
    check(File(path).isFile) { "logback.configFile must point to a file, but was \"$path\" (see .env.example)" }
    return path
}
