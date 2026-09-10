package no.kartverket.altinnpdp.client.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** What the test server should answer with. */
internal data class TestResponse(
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "application/json",
)

/** A request the test server received, so tests can assert on what actually went over the wire. */
internal class RecordedRequest(
    val method: String,
    val body: String,
    private val headers: Map<String, String>,
) {
    /** Header lookup is case-insensitive, as it is on the wire. */
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * A real HTTP server on a loopback port.
 *
 * The clients talk to Altinn through [java.net.http.HttpClient], which is an abstract class with a
 * dozen members to implement, so faking it is far more work than serving the two endpoints for
 * real. The raw base-URL constructors on the clients exist precisely for this - their KDoc points
 * at the [no.kartverket.altinnpdp.client.AltinnEnvironment] constructors as the non-test path.
 */
internal class TestHttpServer private constructor(private val server: HttpServer) : AutoCloseable {

    private val lock = Any()
    private val recorded = mutableMapOf<String, MutableList<RecordedRequest>>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Serves [path] with [handler]. Register before the client under test makes its call. */
    fun on(path: String, handler: (RecordedRequest) -> TestResponse): TestHttpServer {
        server.createContext(path) { exchange ->
            try {
                val request = RecordedRequest(
                    method = exchange.requestMethod,
                    body = exchange.requestBody.readBytes().decodeToString(),
                    headers = exchange.requestHeaders.entries
                        .filter { it.value.isNotEmpty() }
                        .associate { it.key.lowercase() to it.value.first() },
                )
                synchronized(lock) { recorded.getOrPut(path) { mutableListOf() }.add(request) }
                respond(exchange, handler(request))
            } catch (e: Throwable) {
                // Without this the exchange never closes and the client blocks until it times out,
                // turning a broken fixture into a ten-second mystery.
                runCatching { respond(exchange, TestResponse(500, "handler failed: $e", "text/plain")) }
                exchange.close()
            }
        }
        return this
    }

    fun requests(path: String): List<RecordedRequest> = synchronized(lock) { recorded[path].orEmpty().toList() }

    fun requestCount(path: String): Int = requests(path).size

    fun lastRequest(path: String): RecordedRequest =
        requests(path).lastOrNull() ?: error("the test server received no request for $path")

    override fun close() = server.stop(0)

    private fun respond(exchange: HttpExchange, response: TestResponse) {
        val bytes = response.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", response.contentType)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(response.status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        fun start(): TestHttpServer =
            TestHttpServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() })
    }
}
