package sa.arsel.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences

/**
 * Sessions get billed on and funnelled on, so a fabricated one is worse than a missing one. These
 * pin the two ways that happens: a screen rotation, and a three-second task switch.
 */
class SessionTrackerTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: ArselStore
    private lateinit var sent: MutableList<JSONObject>
    private lateinit var tracker: SessionTracker
    private var now = START_MS

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = ArselStore(prefs)
        sent = mutableListOf()
        tracker = SessionTracker(store, eventsInto(store)) { now }
    }

    private fun eventsInto(target: ArselStore) =
        EventController(
            StateManager(target),
            target,
            { req -> sent.add(JSONObject(req.body)) },
            ArselLog(LogLevel.NONE),
        )

    private fun names() = sent.map { it.getString("event") }

    @Test
    fun `first foreground opens a session`() {
        tracker.onForeground()

        assertEquals(listOf(EventBodies.EVENT_SESSION_START), names())
    }

    @Test
    fun `a rotation does not start a second session`() {
        // Rotation tears the last activity down and brings a new one up, briefly hitting zero.
        // Without the gap floor this bills two sessions for every device turned sideways.
        tracker.onForeground()
        tracker.onBackground()
        now += 200
        tracker.onForeground()

        assertEquals(listOf(EventBodies.EVENT_SESSION_START), names())
    }

    @Test
    fun `a brief task switch resumes the same session`() {
        tracker.onForeground()
        tracker.onBackground()
        now += SessionTracker.SESSION_GAP_MS - 1
        tracker.onForeground()

        assertEquals(1, names().size)
    }

    @Test
    fun `reports whether the foreground opened a session`() {
        // The caller refreshes in-app itself only when no session_start did it, so a wrong answer
        // either fetches the catalogue twice per launch or never on a resumed session.
        val first = tracker.onForeground()
        tracker.onBackground()
        now += SessionTracker.SESSION_GAP_MS - 1
        val resumed = tracker.onForeground()

        assertTrue(first)
        assertEquals(false, resumed)
    }

    @Test
    fun `a long absence ends the old session and starts a new one`() {
        tracker.onForeground()
        tracker.onBackground()
        now += SessionTracker.SESSION_GAP_MS + 1
        tracker.onForeground()

        assertEquals(
            listOf(
                EventBodies.EVENT_SESSION_START,
                EventBodies.EVENT_SESSION_END,
                EventBodies.EVENT_SESSION_START,
            ),
            names(),
        )
    }

    @Test
    fun `session end is backdated to when the app actually left`() {
        // Not to when we noticed. A user who comes back the next morning would otherwise record a
        // session that ran all night.
        tracker.onForeground()
        val leftAt = now + 60_000
        now = leftAt
        tracker.onBackground()
        now = leftAt + 8L * 60 * 60 * 1000
        tracker.onForeground()

        val end = sent.first { it.getString("event") == EventBodies.EVENT_SESSION_END }
        assertEquals(EventBodies.iso8601(leftAt), end.getString("timestamp"))
        assertEquals(60, end.getJSONObject("data").getInt(SessionTracker.PROP_DURATION_SECONDS))
    }

    @Test
    fun `a process that died foregrounded does not close its session days later`() {
        // Crash while foregrounded: sessionStartedAt is set, backgroundedAt never was. The end was
        // never observed, so the stale session is dropped unclosed — not ended with a duration
        // spanning the dead days — and the relaunch opens a fresh session.
        tracker.onForeground()
        now += 3L * 24 * 60 * 60 * 1000

        val restarted = ArselStore(prefs)
        val relaunched = SessionTracker(restarted, eventsInto(restarted)) { now }
        relaunched.onForeground()

        assertEquals(
            listOf(EventBodies.EVENT_SESSION_START, EventBodies.EVENT_SESSION_START),
            names(),
        )
    }

    @Test
    fun `a quick crash relaunch resumes the open session`() {
        tracker.onForeground()
        now += SessionTracker.MAX_SESSION_MS - 1

        val restarted = ArselStore(prefs)
        val relaunched = SessionTracker(restarted, eventsInto(restarted)) { now }
        relaunched.onForeground()

        assertEquals(listOf(EventBodies.EVENT_SESSION_START), names())
    }

    @Test
    fun `the session gap exceeds the foreground watcher's rotation debounce`() {
        // The invariant that keeps a rotation from reading as a session boundary: the watcher
        // debounces re-fires for 30s, and the gap must stay comfortably above it.
        assertTrue(SessionTracker.SESSION_GAP_MS > 30_000L)
        assertEquals(30L * 60 * 1000, SessionTracker.SESSION_GAP_MS)
    }

    @Test
    fun `backgrounding without an open session emits nothing`() {
        tracker.onBackground()

        assertTrue(sent.isEmpty())
        assertEquals(0L, store.backgroundedAtMs)
    }

    @Test
    fun `an open session survives a process restart`() {
        // The store is the authority precisely because backgrounding is when we get killed.
        tracker.onForeground()
        tracker.onBackground()

        val restarted = ArselStore(prefs)
        val reopened = SessionTracker(restarted, eventsInto(restarted)) { now }
        now += SessionTracker.SESSION_GAP_MS + 1
        reopened.onForeground()

        assertEquals(
            listOf(
                EventBodies.EVENT_SESSION_START,
                EventBodies.EVENT_SESSION_END,
                EventBodies.EVENT_SESSION_START,
            ),
            names(),
        )
    }

    private companion object {
        /** 2026-08-07T12:00:00Z. */
        const val START_MS = 1_786_104_000_000L
    }
}
