package sa.arsel.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pin the jitter so the curve itself can be asserted. */
private val noJitter: () -> Double = { 0.0 }
private val maxJitter: () -> Double = { 0.999999 }

class RetryPolicyTest {
    @Test
    fun `doubles from the base`() {
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS,
            RetryPolicy.backoffMs(1, null, noJitter),
        )
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS * 2,
            RetryPolicy.backoffMs(2, null, noJitter),
        )
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS * 4,
            RetryPolicy.backoffMs(3, null, noJitter),
        )
    }

    @Test
    fun `caps rather than growing without bound`() {
        assertEquals(
            RetryPolicy.MAX_BACKOFF_MS,
            RetryPolicy.backoffMs(50, null, noJitter),
        )
    }

    /**
     * The whole point: `NetworkType.CONNECTED` is satisfied on every device at once when a network
     * comes back, so an exact curve returns a synchronized fleet to the server.
     */
    @Test
    fun `adds jitter and never subtracts`() {
        val base = RetryPolicy.BASE_BACKOFF_MS
        val jittered = RetryPolicy.backoffMs(1, null, maxJitter)

        assertTrue(jittered > base)
        assertTrue(jittered <= base + base / 2)
    }

    /** A 60s window reset must not be retried at 5s just because that is where the curve starts. */
    @Test
    fun `treats Retry-After as a floor not a ceiling`() {
        assertEquals(60_000L, RetryPolicy.backoffMs(1, 60_000L, noJitter))
    }

    @Test
    fun `keeps the longer of the curve and Retry-After`() {
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS * 32,
            RetryPolicy.backoffMs(6, 1_000L, noJitter),
        )
    }

    /** Every device throttled inside one window gets the SAME Retry-After. */
    @Test
    fun `jitters Retry-After too so a rate-limited fleet does not return in lockstep`() {
        assertTrue(RetryPolicy.backoffMs(1, 60_000L, maxJitter) > 60_000L)
    }

    @Test
    fun `treats a non-positive attempt as the first`() {
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS,
            RetryPolicy.backoffMs(0, null, noJitter),
        )
        assertEquals(
            RetryPolicy.BASE_BACKOFF_MS,
            RetryPolicy.backoffMs(-3, null, noJitter),
        )
    }
}
