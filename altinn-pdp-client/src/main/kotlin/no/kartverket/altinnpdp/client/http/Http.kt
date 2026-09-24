package no.kartverket.altinnpdp.client.http

import no.kartverket.altinnpdp.client.exception.AltinnPdpException
import java.io.IOException

internal object Http {

    fun withoutTrailingSlash(url: String): String = if (url.endsWith("/")) url.dropLast(1) else url

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
