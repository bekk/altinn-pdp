package no.kartverket.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import org.slf4j.LoggerFactory

fun Application.configureAccessLogging() {
    if (!environment.config.boolean("accessLog.enabled", default = true)) return
    install(CallLogging) {
        logger = LoggerFactory.getLogger("access")
    }
}
