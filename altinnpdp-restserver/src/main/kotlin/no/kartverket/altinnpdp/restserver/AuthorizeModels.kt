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
    val organizationNumber: String,
    val action: String,
)

/**
 * Response body of `POST /authorize`. Deliberately minimal - [decision] is the XACML decision
 * name (`PERMIT`, `DENY`, `NOT_APPLICABLE` or `INDETERMINATE`). Kept to just this one field for
 * now so more can be added later (e.g. obligations) without breaking existing callers; removing a
 * field is the breaking direction, adding one isn't.
 */
@Serializable
data class AuthorizeResponse(val decision: String)

/** Body returned for any non-2xx response. */
@Serializable
data class ErrorResponse(val error: String)
