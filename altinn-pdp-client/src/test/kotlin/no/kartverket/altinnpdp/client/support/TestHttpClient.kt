package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.http.JavaOutgoingHttpClient
import no.kartverket.altinnpdp.client.http.OutgoingHttpClient
import java.net.http.HttpClient
import java.time.Duration

internal val testHttpClient: OutgoingHttpClient = JavaOutgoingHttpClient(HttpClient.newHttpClient(), Duration.ofSeconds(10))
