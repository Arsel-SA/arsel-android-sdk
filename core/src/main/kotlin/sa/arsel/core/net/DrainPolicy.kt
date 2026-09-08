package sa.arsel.core.net

/**
 * The two "give up" rules of the queue drain, kept apart from [PushSyncWorker] so they can be
 * asserted without instantiating a WorkManager worker.
 *
 * Both bounds exist because the queue is durable: without them a device that was offline for a
 * fortnight would, on reconnect, replay a fortnight of stale engagement into the analytics
 * pipeline, and a permanently undeliverable queue would hold a wakeup slot forever.
 */
internal object DrainPolicy {
    /**
     * Requests older than this are dropped unsent. Well beyond any realistic offline stretch, and
     * short enough that what does arrive is still worth bucketing.
     */
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Consecutive *failed drains* — not WorkManager wakeups — after which the retry chain is
     * abandoned. [RetryPolicy]'s curve is already at its 5-minute ceiling by the fifth, so a chain
     * this deep is a queue nothing can deliver. The requests stay on disk, and so does the pacing
     * gate: only the wakeup chain is abandoned, and the next enqueue schedules a fresh drain that
     * still waits the gate out.
     *
     * Counting failures rather than `runAttemptCount` is deliberate. A wakeup that arrives while
     * the gate is closed does no network, and must not spend the budget for something it never
     * tried.
     */
    const val MAX_RUN_ATTEMPTS: Int = 8

    fun isExpired(
        createdAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - createdAtMs > MAX_AGE_MS

    /**
     * Whether [RetryPolicy]'s persisted wait still has time left on it. A wakeup that lands inside
     * the wait must do no network — it is the whole reason the wait is persisted rather than left
     * to WorkManager, whose own backoff every enqueue resets.
     */
    fun isGated(
        retryNotBeforeMs: Long,
        nowMs: Long,
    ): Boolean = retryNotBeforeMs > nowMs

    fun hasExhaustedAttempts(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_RUN_ATTEMPTS
}
