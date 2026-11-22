package sa.arsel.core.internal

import sa.arsel.core.store.ArselStore

/**
 * Emits `arsel.session_start` / `arsel.session_end` from foreground and background transitions.
 *
 * **No timers.** A session ends when the app has been in the background longer than
 * [SESSION_GAP_MS], but that fact is only *discovered* on the next foreground — so the end event is
 * emitted then, backdated to the moment the app actually went away. The alternative, a scheduled
 * timer, would have to survive process death and doze to be correct, and would fire in a process
 * that may no longer exist.
 *
 * The consequence, and it is the standard one for mobile analytics: a user who never returns never
 * produces a `session_end`. A session that is open but unclosed is better than a fabricated end
 * time, and the start event already carries everything a funnel needs.
 *
 * State lives in [ArselStore] rather than memory because backgrounding is exactly when the process
 * gets killed — an in-memory `backgroundedAt` would be lost in the case it exists to handle.
 */
internal class SessionTracker(
    private val store: ArselStore,
    private val events: EventController,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** True when this opened a new session, i.e. emitted `arsel.session_start`. */
    fun onForeground(): Boolean {
        val now = clock()
        val openedAt = store.sessionStartedAtMs
        val backgroundedAt = store.backgroundedAtMs

        if (openedAt != 0L) {
            if (backgroundedAt != 0L) {
                // Only a long enough absence rolls the open session over; anything shorter is the
                // same session resuming, which is not an event.
                if (now - backgroundedAt < SESSION_GAP_MS) return false
                endSession(openedAt, backgroundedAt)
            } else if (now - openedAt <= MAX_SESSION_MS) {
                return false // still the same open session (watcher re-fire, or a quick crash relaunch)
            } else {
                // No background was ever recorded and the session is implausibly old: the process
                // died while foregrounded. Its end was never observed, so it is dropped unclosed
                // rather than closed with a duration spanning the dead days.
                store.sessionStartedAtMs = 0L
            }
        }

        store.sessionStartedAtMs = now
        store.backgroundedAtMs = 0L
        events.trackReserved(EventBodies.EVENT_SESSION_START, timestampMs = now)
        return true
    }

    fun onBackground() {
        // Recorded, not emitted. Whether this is the end of a session or a task-switch the user
        // returns from in three seconds is not knowable yet.
        if (store.sessionStartedAtMs == 0L) return
        store.backgroundedAtMs = clock()
    }

    /** Backdated to when the app actually left, not to when we noticed. */
    private fun endSession(
        openedAtMs: Long,
        endedAtMs: Long,
    ) {
        events.trackReserved(
            EventBodies.EVENT_SESSION_END,
            properties = mapOf(PROP_DURATION_SECONDS to (endedAtMs - openedAtMs) / MILLIS_PER_SECOND),
            timestampMs = endedAtMs,
        )
        store.sessionStartedAtMs = 0L
    }

    internal companion object {
        /**
         * 30 minutes, matching the web SDK so a "session" means the same thing on both platforms.
         * Must exceed [ForegroundWatcher]'s rotation floor, or a configuration change would read as
         * a session boundary and every rotation would bill an extra session.
         */
        const val SESSION_GAP_MS = 30L * 60 * 1000

        /**
         * A continuously-foregrounded stretch longer than this with no recorded background can only
         * be a process that died foregrounded — screen-off already counts as a background.
         */
        const val MAX_SESSION_MS = 4L * 60 * 60 * 1000

        const val PROP_DURATION_SECONDS = "duration_seconds"
        private const val MILLIS_PER_SECOND = 1000L
    }
}
