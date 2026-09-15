package no.kartverket.altinnpdp.client.http

import java.time.Duration

/**
 * The three timeouts a PDP lookup is bounded by.
 *
 * @param connect establishing the connection. Only the tighter of two bounds: [request] covers
 *   connecting as well, since the JDK starts its timer before the connection is made.
 * @param request one call, end to end.
 * @param total a whole `PdpClient.authorize(...)`, which on cold caches makes three calls. Not
 *   required to exceed [request].
 */
data class Timeouts(
    val connect: Duration = DEFAULT_CONNECT,
    val request: Duration = DEFAULT_REQUEST,
    val total: Duration = DEFAULT_TOTAL,
) {
    init {
        requirePositive(connect, "connect")
        requirePositive(request, "request")
        requirePositive(total, "total")
    }

    companion object {
        val DEFAULT_CONNECT: Duration = Duration.ofSeconds(5)
        val DEFAULT_REQUEST: Duration = Duration.ofSeconds(10)

        val DEFAULT_TOTAL: Duration = Duration.ofSeconds(20)

        val DEFAULT: Timeouts = Timeouts()

        private fun requirePositive(value: Duration, name: String) {
            require(!value.isNegative && !value.isZero) { "$name timeout must be positive, but was $value" }
        }
    }
}
