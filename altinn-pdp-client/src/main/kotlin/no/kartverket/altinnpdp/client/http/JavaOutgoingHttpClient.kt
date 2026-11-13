package no.kartverket.altinnpdp.client.http

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.time.toKotlinDuration

public class JavaOutgoingHttpClient(
    private val httpClient: HttpClient,
    private val requestTimeout: Duration,
) : OutgoingHttpClient {
    init {
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) {
            "httpClient must not follow redirects, or a redirect would hand our tokens to another host"
        }
        require(requestTimeout.isPositive) {
            "requestTimeout must be positive, but was $requestTimeout"
        }
    }

    override suspend fun send(request: OutgoingRequest): OutgoingResponse {
        val body = request.body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(request.url).method(request.method.name, body)
        request.headers.forEach { (name, value) ->
            try {
                builder.header(name, value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid HTTP request header $name")
            }
        }

        // HttpRequest.timeout stops counting once the headers have arrived, so a server that stalls in the
        // body would leave the call hanging. This timeout covers the whole exchange.
        val exchange = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val response = withTimeoutOrNull(requestTimeout.toKotlinDuration()) { exchange.await() }
            ?: throw HttpTimeoutException("request timed out").also { exchange.cancel(true) }
        return OutgoingResponse(
            statusCode = response.statusCode(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = response.body(),
        )
    }
}
