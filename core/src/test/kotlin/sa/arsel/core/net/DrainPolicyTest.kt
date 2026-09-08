package sa.arsel.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two "give up" rules of the drain. Both exist because the queue is durable: without them a
 * device offline for a fortnight would replay a fortnight of stale engagement on reconnect, and a
 * permanently undeliverable queue would hold a WorkManager wakeup slot forever.
 */
class DrainPolicyTest {
    @Test
    fun `the age ceiling is seven days`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, DrainPolicy.MAX_AGE_MS)
    }

    @Test
    fun `a request younger than the ceiling is still sent`() {
        assertFalse(DrainPolicy.isExpired(NOW_MS - DrainPolicy.MAX_AGE_MS, NOW_MS))
    }

    @Test
    fun `a request past the ceiling is dropped unsent`() {
        assertTrue(DrainPolicy.isExpired(NOW_MS - DrainPolicy.MAX_AGE_MS - 1, NOW_MS))
    }

    @Test
    fun `a clock that jumped backwards does not expire the whole queue`() {
        // A device that just corrected its clock forward-dates everything it enqueued. Treating
        // that as expiry would silently discard a valid registration.
        assertFalse(DrainPolicy.isExpired(NOW_MS + DrainPolicy.MAX_AGE_MS, NOW_MS))
    }

    @Test
    fun `the retry chain is abandoned only after the configured attempts`() {
        assertFalse(DrainPolicy.hasExhaustedAttempts(DrainPolicy.MAX_RUN_ATTEMPTS - 1))
        assertTrue(DrainPolicy.hasExhaustedAttempts(DrainPolicy.MAX_RUN_ATTEMPTS))
        assertTrue(DrainPolicy.hasExhaustedAttempts(DrainPolicy.MAX_RUN_ATTEMPTS + 1))
    }

    @Test
    fun `a queue with no failures behind it is never treated as exhausted`() {
        assertFalse(DrainPolicy.hasExhaustedAttempts(0))
    }

    @Test
    fun `an unset gate never holds the drain back`() {
        assertFalse(DrainPolicy.isGated(0L, NOW_MS))
    }

    @Test
    fun `the gate holds until its instant passes, and not one wakeup longer`() {
        assertTrue(DrainPolicy.isGated(NOW_MS + 1, NOW_MS))
        assertFalse(DrainPolicy.isGated(NOW_MS, NOW_MS))
        assertFalse(DrainPolicy.isGated(NOW_MS - 1, NOW_MS))
    }

    private companion object {
        /** 2026-08-07T12:00:00Z. */
        const val NOW_MS = 1_786_104_000_000L
    }
}
