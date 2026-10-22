package no.kartverket.altinnpdp.client.http

import java.net.URI

public fun interface OutgoingHttpClient {
    public suspend fun send(request: OutgoingRequest): OutgoingResponse
}

public class OutgoingRequest internal constructor(
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
