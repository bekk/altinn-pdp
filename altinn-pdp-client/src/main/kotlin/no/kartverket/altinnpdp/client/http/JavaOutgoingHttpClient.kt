package no.kartverket.altinnpdp.client.http

import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

public class JavaOutgoingHttpClient(
    private val httpClient: HttpClient,
    private val requestTimeout: Duration,
) : OutgoingHttpClient {
    init {
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) {
            "httpClient must not follow redirects, or a redirect would hand our tokens to another host"
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "requestTimeout must be positive, but was $requestTimeout"
        }
    }

    override suspend fun send(request: OutgoingRequest): OutgoingResponse {
        val body = request.body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(request.url)
            .timeout(requestTimeout)
            .method(request.method.name, body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        return OutgoingResponse(
            statusCode = response.statusCode(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = response.body(),
        )
    }
}
