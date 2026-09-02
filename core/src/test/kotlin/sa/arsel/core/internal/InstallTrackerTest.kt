package sa.arsel.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences

/** Both failure modes are permanent: a duplicate, and the upgrade that bills the installed base. */
class InstallTrackerTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: ArselStore
    private lateinit var sent: MutableList<JSONObject>
    private lateinit var tracker: InstallTracker

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = ArselStore(prefs)
        sent = mutableListOf()
        tracker =
            InstallTracker(
                store = store,
                events =
                    EventController(
                        StateManager(store),
                        store,
                        { req -> sent.add(JSONObject(req.body)) },
                        ArselLog(LogLevel.NONE),
                    ),
                appVersion = { APP_VERSION },
                sdkVersion = SDK_VERSION,
            )
    }

    private fun names() = sent.map { it.getString("event") }

    @Test
    fun `a first install emits app_installed`() {
        tracker.reportIfNew(alreadyInstalled = false)

        assertEquals(listOf(EventBodies.EVENT_APP_INSTALLED), names())
    }

    @Test
    fun `app_installed carries the app version, sdk version and platform`() {
        tracker.reportIfNew(alreadyInstalled = false)

        val data = sent.single().getJSONObject("data")
        assertEquals(APP_VERSION, data.getString(InstallTracker.PROP_APP_VERSION))
        assertEquals(SDK_VERSION, data.getString(InstallTracker.PROP_SDK_VERSION))
        assertEquals(InstallTracker.PLATFORM_ANDROID, data.getString(InstallTracker.PROP_PLATFORM))
    }

    @Test
    fun `a later launch emits nothing`() {
        tracker.reportIfNew(alreadyInstalled = false)
        sent.clear()

        tracker.reportIfNew(alreadyInstalled = false)

        assertTrue(names().isEmpty())
    }

    @Test
    fun `a device that predates the sdk is seeded silently`() {
        tracker.reportIfNew(alreadyInstalled = true)

        assertTrue(names().isEmpty())
        assertTrue(store.installReported)
    }

    @Test
    fun `a seeded device stays silent on every later launch`() {
        tracker.reportIfNew(alreadyInstalled = true)
        tracker.reportIfNew(alreadyInstalled = false)

        assertTrue(names().isEmpty())
    }

    @Test
    fun `the seeded path touches no identity`() {
        tracker.reportIfNew(alreadyInstalled = true)

        assertNull(prefs.getString(ArselStore.KEY_ANONYMOUS_ID, null))
        assertNull(prefs.getString(ArselStore.KEY_INSTALLATION_ID, null))
    }

    @Test
    fun `an unavailable app version still emits`() {
        val quiet = mutableListOf<JSONObject>()
        InstallTracker(
            store = store,
            events =
                EventController(
                    StateManager(store),
                    store,
                    { req -> quiet.add(JSONObject(req.body)) },
                    ArselLog(LogLevel.NONE),
                ),
            appVersion = { null },
            sdkVersion = SDK_VERSION,
        ).reportIfNew(alreadyInstalled = false)

        val data = quiet.single().getJSONObject("data")
        assertTrue(quiet.single().getString("event") == EventBodies.EVENT_APP_INSTALLED)
        assertTrue(!data.has(InstallTracker.PROP_APP_VERSION))
    }

    private companion object {
        const val APP_VERSION = "4.0.8"
        const val SDK_VERSION = "1.2.0"
    }
}
