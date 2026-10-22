package no.kartverket.altinnpdp.client.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JavaOutgoingHttpClientTest {

    private val noRedirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun serving(handle: (HttpExchange) -> Unit, block: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/", handle)
            start()
        }
        try {
            block(URI("http://localhost:${server.address.port}/authorization/api/v1/authorize"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `refuses an HttpClient that follows redirects`() {
        val following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        val e = assertFailsWith<IllegalArgumentException> { JavaOutgoingHttpClient(following, Duration.ofSeconds(3)) }

        assertContains(e.message!!, "redirects")
    }

    @Test
    fun `rejects a request timeout that is not positive`() {
        val e = assertFailsWith<IllegalArgumentException> { JavaOutgoingHttpClient(noRedirects, Duration.ZERO) }

        assertContains(e.message!!, "positive")
    }

    @Test
    fun `sends the method, headers and body`() {
        val received = CompletableFuture<Triple<String, String?, ByteArray>>()
        val body = "grant_type=x&assertion=y".toByteArray()
        serving(
            { exchange ->
                received.complete(
                    Triple(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes()),
                )
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            },
        ) { url ->
            runBlocking {
                JavaOutgoingHttpClient(noRedirects, Duration.ofSeconds(3))
                    .send(OutgoingRequest(HttpMethod.POST, url, mapOf("Authorization" to "Bearer test-token"), body))
            }
        }

        val (method, authorization, receivedBody) = received.get()
        assertEquals("POST", method)
        assertEquals("Bearer test-token", authorization)
        assertContentEquals(body, receivedBody)
    }

    @Test
    fun `returns the status, content type and body bytes as the server sent them`() {
        val body = """{"error":"ikke funnet: æøå"}""".toByteArray(Charsets.ISO_8859_1)
        serving(
            { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json;charset=ISO-8859-1")
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        ) { url ->
            val response = runBlocking {
                JavaOutgoingHttpClient(noRedirects, Duration.ofSeconds(3)).send(OutgoingRequest(HttpMethod.GET, url, emptyMap()))
            }

            assertEquals(404, response.statusCode)
            assertEquals("application/json;charset=ISO-8859-1", response.contentType)
            assertContentEquals(body, response.body)
        }
    }

    @Test
    fun `gives up when the server does not answer within the request timeout`() {
        val answer = CountDownLatch(1)
        serving({ exchange -> answer.await().also { exchange.close() } }) { url ->
            try {
                assertFailsWith<HttpTimeoutException> {
                    runBlocking {
                        withTimeout(5_000) {
                            JavaOutgoingHttpClient(noRedirects, Duration.ofMillis(100)).send(OutgoingRequest(HttpMethod.GET, url, emptyMap()))
                        }
                    }
                }
            } finally {
                answer.countDown()
            }
        }
    }
}
