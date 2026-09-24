package no.kartverket.altinnpdp.client.http

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.exception.AltinnPdpException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.toKotlinDuration

internal object Http {

    fun defaultClient(): HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Altinn and Maskinporten answer with more fields than we model. */
    val json = Json { ignoreUnknownKeys = true }

    fun url(baseUrl: String, path: String): URI = URI.create(baseUrl.removeSuffix("/") + path)

    suspend fun sendExpectingOk(
        httpClient: HttpClient,
        request: HttpRequest,
        target: String,
        exception: (message: String, statusCode: Int?, responseBody: String?, cause: Throwable?) -> AltinnPdpException,
    ): HttpResponse<String> {
        val response = try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw exception("Call to $target failed: ${e.message}", null, null, e)
        }
        if (response.statusCode() != 200) {
            throw exception("$target responded ${response.statusCode()}", response.statusCode(), response.body(), null)
        }
        return response
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
