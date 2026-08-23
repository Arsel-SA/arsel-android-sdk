package sa.arsel.core.net

import sa.arsel.core.BuildConfig
import sa.arsel.core.log.ArselLog
import java.io.BufferedReader
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal zero-dependency HTTPS JSON client (no OkHttp). MUST be called off the main thread.
 * Takes plain baseUrl/timeout (not the whole config) so [PushSyncWorker] can build it from
 * persisted state after a cold process restart, without depending on Registry being initialized.
 */
internal class ApiClient(
    private val baseUrl: String,
    private val timeoutMs: Long,
    private val log: ArselLog,
) {
    enum class Result { SUCCESS, RETRYABLE, REAUTH, PERMANENT }

    /**
     * The body is carried because registration's one-time `deviceSecret` lives in it — a client
     * that only classified the status code would drop the one value that can never be re-fetched.
     */
    class Response(
        val result: Result,
        val code: Int,
        val body: String?,
        /** Parsed `Retry-After`, when the server asked us to wait a specific amount. */
        val retryAfterMs: Long?,
    )

    /**
     * @param extraHeaders per-request headers, e.g. `X-Arsel-Device-Auth`.
     * @param authenticated true when the request carried a device secret, which changes how 401 /
     *   403 / 404 are classified.
     */
    fun post(
        path: String,
        jsonBody: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
    ): Response {
        var conn: HttpsURLConnection? = null
        return try {
            // baseUrl is HTTPS-validated in ArselConfig.Builder.build()
            conn =
                (URL(baseUrl + path).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty(HEADER_SDK, "android/${BuildConfig.SDK_VERSION}")
                    extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val result = classify(code, authenticated)
            val body =
                if (result == Result.SUCCESS) {
                    readBounded(conn.inputStream)
                } else {
                    readBounded(conn.errorStream)
                }
            if (result != Result.SUCCESS) {
                log.w("POST $path -> $code ($result)${body?.take(ERROR_SNIPPET_CHARS)?.let { "; $it" }.orEmpty()}")
            }
            Response(result, code, body, parseRetryAfterMs(conn.getHeaderField(HEADER_RETRY_AFTER)))
        } catch (t: Throwable) {
            log.w("POST $path failed (network) — will retry", t)
            Response(Result.RETRYABLE, CODE_NO_RESPONSE, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Conditional GET, for the in-app message catalogue.
     *
     * A separate method rather than a mode flag on [post]: `doOutput = true` there forces POST
     * semantics onto HttpsURLConnection, so that method cannot be coerced into a GET even with an
     * empty body. This is also the only request in the SDK whose 304 is a success.
     */
    fun get(
        path: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
    ): Response {
        var conn: HttpsURLConnection? = null
        return try {
            conn =
                (URL(baseUrl + path).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty(HEADER_SDK, "android/${BuildConfig.SDK_VERSION}")
                    extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                }

            val code = conn.responseCode
            val result = classify(code, authenticated)
            // A 304 is a success carrying no body at all; asking for the stream would only ever
            // yield null.
            val body =
                when {
                    code == HTTP_NOT_MODIFIED -> null
                    result == Result.SUCCESS -> readBounded(conn.inputStream)
                    else -> readBounded(conn.errorStream)
                }
            if (result != Result.SUCCESS) {
                log.w("GET $path -> $code ($result)${body?.take(ERROR_SNIPPET_CHARS)?.let { "; $it" }.orEmpty()}")
            }
            Response(result, code, body, parseRetryAfterMs(conn.getHeaderField(HEADER_RETRY_AFTER)))
        } catch (t: Throwable) {
            log.w("GET $path failed (network) — will retry", t)
            Response(Result.RETRYABLE, CODE_NO_RESPONSE, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBounded(stream: InputStream?): String? =
        runCatching {
            stream?.bufferedReader()?.use(BufferedReader::readText)?.take(MAX_BODY_CHARS)
        }.getOrNull()

    companion object {
        /**
         * Status → retry policy. Pure, and deliberately so: this is the decision that separates a
         * device that eventually registers from one that gives up forever, and it is the only part
         * of the transport a unit test can pin down.
         */
        fun classify(
            code: Int,
            authenticated: Boolean,
        ): Result =
            when {
                code in HTTP_OK..HTTP_SUCCESS_MAX -> Result.SUCCESS
                // The cached in-app catalogue is still current. Without this branch a conditional
                // GET falls through to PERMANENT below and the cache is discarded on every
                // successful revalidation — the opposite of what the request asked for.
                code == HTTP_NOT_MODIFIED -> Result.SUCCESS
                code == HTTP_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS ||
                    code in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> Result.RETRYABLE
                // On an authed route these all mean the same thing behind the backend's deliberately
                // opaque 404: this secret is no longer accepted. Re-registering mints a new one;
                // retrying with the old one never succeeds.
                authenticated && (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN || code == HTTP_NOT_FOUND) ->
                    Result.REAUTH
                // Registration answers a bare 404 for an unknown push key *and* for an org whose push
                // channel is not switched on yet. The second is ordinary onboarding — a device that gave
                // up permanently here would never register once the customer finishes setup.
                code == HTTP_NOT_FOUND -> Result.RETRYABLE
                else -> Result.PERMANENT
            }

        /**
         * RFC 7231 allows either delta-seconds or an HTTP-date; servers in the wild send both.
         *
         * @param nowMs injected so the HTTP-date branch is deterministic under test.
         */
        fun parseRetryAfterMs(
            rawHeader: String?,
            nowMs: Long = System.currentTimeMillis(),
        ): Long? {
            val header = rawHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            header.toLongOrNull()?.let { return (it * MILLIS_PER_SECOND).coerceAtLeast(0L) }
            return runCatching {
                val parsed =
                    SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("GMT") }
                        .parse(header)
                        ?: return null
                (parsed.time - nowMs).coerceAtLeast(0L)
            }.getOrNull()
        }

        const val HEADER_DEVICE_AUTH = "X-Arsel-Device-Auth"
        const val HEADER_AUTHORIZATION = "Authorization"

        /** 24h server-side window; the value is the queued request's own persisted id. */
        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        const val BEARER_PREFIX = "Bearer "
        private const val HEADER_SDK = "X-Arsel-SDK"
        private const val HEADER_RETRY_AFTER = "Retry-After"
        private const val HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
        private const val MILLIS_PER_SECOND = 1000L
        private const val MAX_BODY_CHARS = 4096
        private const val ERROR_SNIPPET_CHARS = 200

        /** Public: the catalogue fetch branches on it before reading a body. */
        const val HTTP_NOT_MODIFIED = 304

        private const val HTTP_OK = 200
        private const val HTTP_SUCCESS_MAX = 299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_MIN = 500
        private const val HTTP_SERVER_ERROR_MAX = 599

        /** No status line at all (DNS failure, socket reset, TLS error). */
        const val CODE_NO_RESPONSE = -1
    }
}
