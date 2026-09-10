package no.kartverket.altinnpdp.restserver

import kotlinx.serialization.Serializable

/**
 * Body of `POST /authorize`. Mirrors the arguments `PdpClient.authorize` needs directly - the
 * Altinn subscription key and token are configured server-side (see [configurePdp]), never
 * supplied by the caller.
 */
@Serializable
data class AuthorizeRequest(
    val systemuserId: String,
    val resourceId: String,
    /**
     * The plain Norwegian org number (9 digits, e.g. `"923609016"`) of the party whose access is
     * being checked - the customer/business systemuserId is acting on behalf of, not the calling
     * system's own org number.
     */
    val organizationNumber: String,
    val action: String,
) {
    /**
     * Called explicitly from the route rather than an `init` block - an `init` check would run
     * during JSON deserialization, where kotlinx.serialization wraps the resulting
     * [IllegalArgumentException] into a generic "malformed request body" error instead of the
     * specific message below.
     */
    fun requireValidOrganizationNumber() {
        require(organizationNumber.matches(ORG_NUMBER_REGEX)) {
            "organizationNumber must be exactly 9 digits"
        }
    }
}

private val ORG_NUMBER_REGEX = Regex("""\d{9}""")

/**
 * Response body of `POST /authorize`. [permit] is the simple yes/no most callers only need;
 * [decision] is the underlying XACML decision name (`PERMIT`, `DENY`, `NOT_APPLICABLE` or
 * `INDETERMINATE`) for callers that want to distinguish an explicit deny from "no policy applies".
 */
@Serializable
data class AuthorizeResponse(val permit: Boolean, val decision: String)

/** Body returned for any non-2xx response. */
@Serializable
data class ErrorResponse(val error: String)
