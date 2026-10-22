package no.kartverket.altinnpdp.client.exception

import no.kartverket.altinnpdp.client.validation.ValidationError

public class ValidationException(
    public val errors: List<ValidationError>,
) : RuntimeException(errors.joinToString("; ") { it.message })
