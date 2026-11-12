package no.kartverket.altinnpdp.restserver.observability

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import java.util.UUID
internal const val CLIENT_CERTIFICATE_HEADER = "X-Forwarded-Client-Cert"

// The proxy appends its entry after any the caller sent, so only the last entry can be trusted.
internal fun workloadFrom(clientCertificate: String?): String? =
    clientCertificate
        ?.substringAfterLast(',')
        ?.split(';')
        ?.map { it.trim() }
        ?.lastOrNull { it.startsWith("URI=spiffe://") }
        ?.removePrefix("URI=")

private val safeRequestId = Regex("[A-Za-z0-9._:-]{1,128}")

internal fun isSafeRequestId(requestId: String): Boolean = safeRequestId.matches(requestId)

internal fun Application.installCallLogging(accessLog: Boolean, accessLogHealth: Boolean) {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify(::isSafeRequestId)
    }
    install(CallLogging) {
        mdc("workload") { workloadFrom(it.request.headers[CLIENT_CERTIFICATE_HEADER]) }
        callIdMdc("request_id")

        logger = LoggerFactory.getLogger("access")
        disableDefaultColors()
        filter { accessLog && (accessLogHealth || !it.request.path().startsWith("/health/")) }
    }
}
