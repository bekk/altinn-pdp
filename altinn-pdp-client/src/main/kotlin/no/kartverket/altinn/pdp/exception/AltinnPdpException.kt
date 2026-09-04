package no.kartverket.altinn.pdp.exception

/**
 * Base class for every error this library throws.
 *
 * When the error came from an HTTP response, [statusCode] and [responseBody] carry the response
 * itself, so callers can act on it without parsing [message] - retry a 503, or refetch after a
 * 401, for example.
 */
sealed class AltinnPdpException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val responseBody: String? = null,
) : RuntimeException(message, cause) {

    companion object {
        /** How much of a response body is kept in the message, to keep error pages out of the logs. */
        private const val MAX_BODY_LENGTH = 500

        /**
         * [message] with an abbreviated [body] appended, for a shared constructor pattern across
         * every subclass - the body is kept whole in [responseBody], only the message is capped.
         */
        internal fun messageWithBody(message: String, body: String?): String {
            val shown = abbreviate(body)
            return if (shown.isEmpty()) message else "$message: $shown"
        }

        private fun abbreviate(body: String?): String {
            if (body == null) return ""
            return if (body.length <= MAX_BODY_LENGTH) {
                body
            } else {
                "${body.take(MAX_BODY_LENGTH)}… (${body.length} characters in total)"
            }
        }
    }
}
