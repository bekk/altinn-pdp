package no.kartverket.altinnpdp.client.http

import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Takes the request timeout itself, because `java.net.http` can only set it per request. */
public class JavaPdpHttpClient(
    private val httpClient: HttpClient,
    private val requestTimeout: Duration,
) : PdpHttpClient {
    init {
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) {
            "httpClient must not follow redirects, or a redirect would hand our tokens to another host"
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "requestTimeout must be positive, but was $requestTimeout"
        }
    }

    override suspend fun send(request: PdpHttpRequest): PdpHttpResponse {
        val body = request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(request.url)
            .timeout(requestTimeout)
            .method(request.method, body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        return PdpHttpResponse(response.statusCode(), response.body())
    }
}
