package no.kartverket.altinnpdp.restserver.models

object ErrorCode {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val MALFORMED_BODY = "MALFORMED_BODY"
    const val UPSTREAM_REJECTED = "UPSTREAM_REJECTED"
    const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
