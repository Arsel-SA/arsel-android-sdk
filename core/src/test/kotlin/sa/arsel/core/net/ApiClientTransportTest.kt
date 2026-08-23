package sa.arsel.core.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The socket half of [ApiClient], over plain http.
 *
 * `openConnection()` returns the non-TLS `HttpURLConnection` for an http URL, so casting to
 * `HttpsURLConnection` throws — and `post()` swallows every `Throwable` as a retryable network
 * error, which turns the mistake into a device that silently retries forever instead of failing
 * loudly. A loopback baseUrl is a supported configuration, so this path has to actually work.
 */
class ApiClientTransportTest {
    private lateinit var server: ServerSocket
    private lateinit var baseUrl: String

    @Volatile private var requestLine: String? = null

    @Volatile private var requestBody: String? = null

    @Before
    fun start() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    requestLine = input.readLine()
                    var length = 0
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = CharArray(length)
                    if (length > 0) input.read(body, 0, length)
                    requestBody = String(body)

                    val payload = """{"status":"accepted"}"""
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 202 Accepted\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${payload.length}\r\n\r\n" +
                                payload
                        ).toByteArray(),
                    )
                }
            }
        }
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `posts over plain http to a loopback backend`() {
        val client = ApiClient(baseUrl, 5_000L, ArselLog(LogLevel.NONE))

        val response = client.post("/v1/events/send", """{"event":"product.viewed"}""")

        assertEquals(ApiClient.Result.SUCCESS, response.result)
        assertEquals(202, response.code)
        assertTrue(response.body!!.contains("accepted"))
        assertEquals("POST /v1/events/send HTTP/1.1", requestLine)
        assertEquals("""{"event":"product.viewed"}""", requestBody)
    }
}
