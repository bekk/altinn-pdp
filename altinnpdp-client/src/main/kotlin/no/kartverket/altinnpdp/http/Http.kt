package no.kartverket.altinnpdp.http

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.future.await
import no.kartverket.altinnpdp.exception.AltinnPdpException

/** Small helpers around [HttpClient] shared by every outbound call this library makes. */
internal object Http {
    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

    fun defaultClient(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(DEFAULT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Strips a single trailing slash so a base URL can be concatenated with a path safely. */
    fun withoutTrailingSlash(url: String): String = if (url.endsWith("/")) url.dropLast(1) else url

    suspend fun send(
        httpClient: HttpClient,
        request: HttpRequest,
        target: String,
        exception: (message: String, cause: Throwable) -> AltinnPdpException,
    ): HttpResponse<String> =
        try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw exception("Call to $target failed: ${e.message}", e)
        }
}
