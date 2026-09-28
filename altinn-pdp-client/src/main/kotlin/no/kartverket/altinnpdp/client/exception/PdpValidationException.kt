package no.kartverket.altinnpdp.client.exception

import no.kartverket.altinnpdp.client.validation.PdpValidationError

public class PdpValidationException(
    public val errors: List<PdpValidationError>,
) : RuntimeException(errors.joinToString("; ") { it.message })
