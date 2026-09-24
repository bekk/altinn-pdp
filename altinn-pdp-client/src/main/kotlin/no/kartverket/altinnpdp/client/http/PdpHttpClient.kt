package no.kartverket.altinnpdp.client.http

import java.io.IOException
import java.net.URI

/**
 * Sends the library's HTTP calls, so timeouts, proxy, TLS and logging are whatever the implementation
 * sets. It must not follow redirects, and must throw an [IOException] when a call fails or times out.
 */
fun interface PdpHttpClient {
    suspend fun send(request: PdpHttpRequest): PdpHttpResponse
}

// Not data classes, so a toString in someone's logs cannot print the bearer token.
class PdpHttpRequest(
    val method: String,
    val url: URI,
    val headers: Map<String, String>,
    val body: String? = null,
)

class PdpHttpResponse(
    val statusCode: Int,
    val body: String,
)
