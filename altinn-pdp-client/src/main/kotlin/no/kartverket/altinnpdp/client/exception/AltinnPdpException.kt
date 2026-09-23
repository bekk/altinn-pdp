package no.kartverket.altinnpdp.client.exception

sealed class AltinnPdpException(
    message: String,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(messageWithBody(message, responseBody), cause) {

    companion object {
        /** Keeps large error pages out of the logs. */
        private const val MAX_BODY_LENGTH = 500

        private fun messageWithBody(message: String, body: String?): String {
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
