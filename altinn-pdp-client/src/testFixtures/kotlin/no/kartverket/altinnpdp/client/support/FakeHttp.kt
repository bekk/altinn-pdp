package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.http.OutgoingResponse

public fun response(statusCode: Int, contentType: String, body: String): OutgoingResponse =
    OutgoingResponse(statusCode, contentType, body.encodeToByteArray())
