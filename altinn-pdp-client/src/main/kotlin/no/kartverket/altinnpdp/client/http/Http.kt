package no.kartverket.altinnpdp.client.http

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import no.kartverket.altinnpdp.client.exception.AltinnPdpException

internal object Http {

    fun defaultClient(timeouts: Timeouts): HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeouts.connect)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

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

    /** `T : Any` so that a null from `withTimeoutOrNull` can only mean the budget expired. */
    suspend fun <T : Any> withBudget(
        budget: Duration,
        operation: String,
        exception: (message: String) -> AltinnPdpException,
        block: suspend () -> T,
    ): T =
        withTimeoutOrNull(budget.toKotlinDuration()) { block() }
            ?: throw exception("$operation did not complete within its ${format(budget)} time budget")

    /** `Duration.toString` would put `PT20S` in the message. */
    private fun format(duration: Duration): String =
        if (duration.nano == 0) "${duration.seconds} s" else "${duration.toMillis()} ms"
}
