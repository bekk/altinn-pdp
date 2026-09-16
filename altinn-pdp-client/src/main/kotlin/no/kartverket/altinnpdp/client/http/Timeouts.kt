package no.kartverket.altinnpdp.client.http

import java.time.Duration

/** [connect] is the tighter of two bounds: the JDK starts [request]'s timer before connecting. */
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
