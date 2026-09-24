package no.kartverket.altinnpdp.client.exception

import no.kartverket.altinnpdp.client.validation.PdpValidationError

class PdpValidationException(
    val errors: List<PdpValidationError>,
) : RuntimeException(errors.joinToString("; ") { it.message })
