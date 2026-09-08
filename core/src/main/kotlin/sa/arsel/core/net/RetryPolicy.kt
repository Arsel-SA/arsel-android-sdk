package sa.arsel.core.net

/**
 * Retry pacing, mirrored deliberately in the web and iOS SDKs so a fleet behaves the same whichever
 * platform it is on.
 *
 * The jitter is the point. Every drain trigger this SDK has — `NetworkType.CONNECTED` being
 * satisfied, app foreground, a scheduled retry — fires on every device at the same instant when a
 * network comes back or a backend recovers. An unjittered curve turns that into a synchronized wall
 * of requests precisely when the server is least able to take it, and each rejection
 * re-synchronizes the fleet for the next round.
 *
 * This is *not* WorkManager's backoff. That one is reset by every `APPEND_OR_REPLACE` enqueue —
 * which is the right policy for scheduling, but meant an active app retried every ~10s forever. The
 * wait computed here is persisted, so it survives the reset.
 */
internal object RetryPolicy {
    const val BASE_BACKOFF_MS: Long = 5_000

    const val MAX_BACKOFF_MS: Long = 5 * 60_000

    /** Doubling stops here; `5s shl 6` is already past the 5-minute ceiling. */
    private const val MAX_DOUBLINGS = 6

    /**
     * How long to wait before retrying, given the attempt number (1-based) and whatever the server
     * asked for.
     *
     * `Retry-After` is a floor, never a ceiling: the server knows when its window rolls and we must
     * not come back before it. But it is jittered on top of, because a whole fleet rate-limited
     * inside one window receives the *same* `Retry-After` and would otherwise return in lockstep the
     * moment it expires.
     *
     * @param random injected so the curve is deterministic under test.
     */
    fun backoffMs(
        attempt: Int,
        retryAfterMs: Long?,
        random: () -> Double = { Math.random() },
    ): Long {
        val doublings = minOf(maxOf(attempt, 1) - 1, MAX_DOUBLINGS)
        val exponential = minOf(BASE_BACKOFF_MS shl doublings, MAX_BACKOFF_MS)
        val floor = maxOf(retryAfterMs ?: 0L, exponential)
        return floor + (random() * (floor / 2)).toLong()
    }
}
