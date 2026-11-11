package no.kartverket.altinnpdp.client.http

import java.net.URI

/**
 * Must not follow redirects, since the calls carry tokens, and must throw an [java.io.IOException] when a call
 * fails. Any other exception reaches the caller as it is, instead of as an
 * [no.kartverket.altinnpdp.client.AltinnPdpException].
 */
public fun interface OutgoingHttpClient {
    public suspend fun send(request: OutgoingRequest): OutgoingResponse
}

public class OutgoingRequest(
    public val method: HttpMethod,
    public val url: URI,
    public val headers: Map<String, String>,
    public val body: ByteArray? = null,
)

public class OutgoingResponse(
    public val statusCode: Int,
    public val contentType: String?,
    public val body: ByteArray,
)
