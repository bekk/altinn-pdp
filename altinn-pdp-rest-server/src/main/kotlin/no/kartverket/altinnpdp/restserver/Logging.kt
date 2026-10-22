package no.kartverket.altinnpdp.restserver

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
