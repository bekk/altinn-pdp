package no.kartverket.altinnpdp.client.http

import java.net.URI

public fun interface PdpHttpClient {
    public suspend fun send(request: PdpHttpRequest): PdpHttpResponse
}

public class PdpHttpRequest internal constructor(
    public val method: String,
    public val url: URI,
    public val headers: Map<String, String>,
    public val body: String? = null,
)

public class PdpHttpResponse(
    public val statusCode: Int,
    public val body: String,
)
