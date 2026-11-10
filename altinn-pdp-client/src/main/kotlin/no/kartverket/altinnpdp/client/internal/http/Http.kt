package no.kartverket.altinnpdp.client.internal.http

import kotlinx.serialization.json.Json
import no.kartverket.altinnpdp.client.AltinnPdpException
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingRequest
import no.kartverket.altinnpdp.client.http.OutgoingResponse
import java.io.IOException
import java.net.URI

internal object Http {

    val json = Json { ignoreUnknownKeys = true }

    fun url(baseUrl: String, path: String): URI = URI.create(baseUrl.trimEnd('/') + path)

    suspend fun sendExpectingOk(
        httpClient: OutgoingHttpClient,
        request: OutgoingRequest,
        target: String,
        exception: (message: String, statusCode: Int?, responseBody: String?, cause: Throwable?) -> AltinnPdpException,
    ): OutgoingResponse {
        val response = try {
            httpClient.send(request)
        } catch (e: IOException) {
            throw exception("Call to $target failed: ${e.message}", null, null, e)
        }
        if (response.statusCode != 200) {
            throw exception("$target responded ${response.statusCode}", response.statusCode, response.body.decodeToString(), null)
        }
        return response
    }
}
