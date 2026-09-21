package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.calllogging.CallLogging

fun Application.configureAccessLogging() {
    if (!accessLogEnabled(environment.config)) return
    install(CallLogging)
}

internal fun accessLogEnabled(config: ApplicationConfig = MapApplicationConfig()): Boolean {
    val raw = config.propertyOrNull("accessLog.enabled")?.getString()?.trim()?.takeIf { it.isNotBlank() }
        ?: return true
    return raw.toBooleanStrictOrNull()
        ?: error("accessLog.enabled must be true or false, but was \"$raw\" (see .env.example)")
}
