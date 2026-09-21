package no.kartverket.altinnpdp.client.exception

import no.kartverket.altinnpdp.client.validation.PdpValidationError

/** Extends [IllegalArgumentException] so callers of the previous per-field checks still catch it. */
class PdpValidationException(
    val errors: List<PdpValidationError>,
) : IllegalArgumentException(errors.joinToString("; ") { it.message })
