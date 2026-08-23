package sa.arsel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config rules for [ArselConfig].
 *
 * Two things are pinned here. First the transport rule: HTTPS is mandatory in production, but a
 * loopback backend is how an integrator develops — the web and iOS SDKs both exempt it, and
 * rejecting it here is what strands an Android integrator on day one.
 *
 * Second, that [ArselConfig.Builder.build] never throws. It used to `require`, which meant an
 * invalid key crashed the host app from `Application.onCreate` — the SDK's own invariant is that it
 * never takes the host down, and the failure is a developer mistake that should cost telemetry
 * rather than a launch. Validation is reported to [Arsel.initialize] instead.
 */
class ArselConfigTest {
    private fun build(
        url: String,
        key: String = "pub_test",
    ) = ArselConfig.Builder(key, url).build()

    @Test
    fun `loopback and emulator host are allowed over http`() {
        for (url in listOf(
            "http://localhost:8076",
            "http://127.0.0.1:8076",
            // The only address an Android emulator can reach the developer's host on.
            "http://10.0.2.2:8076",
        )) {
            assertEquals(url, build(url).baseUrl)
            assertNull(build(url).validationError())
        }
    }

    @Test
    fun `plain http to any other host is reported, not thrown`() {
        for (url in listOf("http://api.arsel.sa", "http://example.com", "http://192.168.1.5:8076")) {
            val error = build(url).validationError()
            assertTrue("expected an HTTPS complaint for $url, got $error", error?.contains("HTTPS") == true)
        }
    }

    @Test
    fun `https is always allowed`() {
        assertEquals("https://api.arsel.sa", build("https://api.arsel.sa").baseUrl)
        assertNull(build("https://api.arsel.sa").validationError())
    }

    @Test
    fun `a blank client key is reported`() {
        val error = build("https://api.arsel.sa", key = "  ").validationError()
        assertTrue(error?.contains("clientKey is required") == true)
    }

    /** The rule that catches a secret API key compiled into an APK anyone can unzip. */
    @Test
    fun `a non-publishable client key is reported`() {
        val error = build("https://api.arsel.sa", key = "sk_live_secret").validationError()
        assertTrue(error?.contains("never a secret API key") == true)
    }
}
