package no.kartverket.altinnpdp.client.http

import java.io.IOException
import java.net.URI

/**
 * Sends the library's HTTP calls, so timeouts, proxy, TLS and logging are whatever the implementation
 * sets. It must not follow redirects, and must throw an [IOException] when a call fails or times out.
 */
public fun interface PdpHttpClient {
    public suspend fun send(request: PdpHttpRequest): PdpHttpResponse
}

// Not data classes, so a toString in someone's logs cannot print the bearer token.
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
