package no.kartverket.altinnpdp.client.exception

/**
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
        /** Keeps large error pages out of the logs. */
        private const val MAX_BODY_LENGTH = 500

        /** The body is kept whole in [responseBody]; only the message is capped. */
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
