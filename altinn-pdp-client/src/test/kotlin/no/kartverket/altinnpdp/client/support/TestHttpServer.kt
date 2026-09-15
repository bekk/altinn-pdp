package no.kartverket.altinnpdp.client.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

internal data class TestResponse(
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "application/json",
)

internal class RecordedRequest(
    val method: String,
    val body: String,
    private val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal class TestHttpServer private constructor(private val server: HttpServer) : AutoCloseable {

    private val lock = Any()
    private val recorded = mutableMapOf<String, MutableList<RecordedRequest>>()
    private val handlers = mutableMapOf<String, (RecordedRequest) -> TestResponse>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    // HttpServer.createContext throws if the same path is registered twice, so the context is
    // created once and the handler looked up per request. Re-registering a path therefore replaces
    // the handler, which lets one test answer differently partway through.
    fun on(path: String, handler: (RecordedRequest) -> TestResponse): TestHttpServer {
        val isNewPath = synchronized(lock) { handlers.put(path, handler) == null }
        if (!isNewPath) return this

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
                val current = synchronized(lock) { handlers.getValue(path) }
                respond(exchange, current(request))
            } catch (e: Throwable) {
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
        // -1 is HttpExchange's "no response body". Passing 0 instead means a chunked body of
        // unknown length, and the exchange would never complete - the client blocks until its
        // timeout rather than failing fast.
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
