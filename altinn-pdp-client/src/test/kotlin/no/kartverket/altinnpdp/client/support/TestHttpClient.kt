package no.kartverket.altinnpdp.client.support

import no.kartverket.altinnpdp.client.http.JavaPdpHttpClient
import no.kartverket.altinnpdp.client.http.PdpHttpClient
import java.net.http.HttpClient
import java.time.Duration

internal val testHttpClient: PdpHttpClient = JavaPdpHttpClient(HttpClient.newHttpClient(), Duration.ofSeconds(10))
