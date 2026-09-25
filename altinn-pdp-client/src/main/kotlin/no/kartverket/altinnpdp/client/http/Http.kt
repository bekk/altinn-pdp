package no.kartverket.altinnpdp.client.http

import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.exception.AltinnPdpException
import java.io.IOException
import java.net.URI

internal object Http {

    /** Altinn and Maskinporten answer with more fields than we model. */
    val json = Json { ignoreUnknownKeys = true }

    fun url(baseUrl: String, path: String): URI = URI.create(baseUrl.removeSuffix("/") + path)

    suspend fun sendExpectingOk(
        httpClient: PdpHttpClient,
        request: PdpHttpRequest,
        target: String,
        exception: (message: String, statusCode: Int?, responseBody: String?, cause: Throwable?) -> AltinnPdpException,
    ): PdpHttpResponse {
        val response = try {
            httpClient.send(request)
        } catch (e: IOException) {
            throw exception("Call to $target failed: ${e.message}", null, null, e)
        }
        if (response.statusCode != 200) {
            throw exception("$target responded ${response.statusCode}", response.statusCode, response.body, null)
        }
        return response
    }
}
