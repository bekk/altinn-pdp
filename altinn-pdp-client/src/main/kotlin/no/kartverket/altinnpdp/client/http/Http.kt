package no.kartverket.altinnpdp.client.http

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import no.kartverket.altinnpdp.client.exception.AltinnPdpException

/** Small helpers around [HttpClient] shared by every outbound call this library makes. */
internal object Http {

    fun defaultClient(timeouts: Timeouts = Timeouts.DEFAULT): HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeouts.connect)
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

    suspend fun <T> withBudget(
        budget: Duration,
        operation: String,
        exception: (message: String, cause: Throwable) -> AltinnPdpException,
        block: suspend () -> T,
    ): T =
        try {
            // The kotlin.time overload: the Long one takes milliseconds and would round a
            // sub-millisecond budget down to zero, failing every call instantly.
            withTimeout(budget.toKotlinDuration()) { block() }
        } catch (e: TimeoutCancellationException) {
            throw exception("$operation did not complete within its ${format(budget)} time budget", e)
        }

    /** `Duration.toString` would put `PT20S` in the message. */
    private fun format(duration: Duration): String =
        if (duration.nano == 0) "${duration.seconds} s" else "${duration.toMillis()} ms"
}
