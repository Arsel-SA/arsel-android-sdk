package sa.arsel.core.inapp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences
import kotlin.coroutines.CoroutineContext

/**
 * A push wakes thousands of processes at once; each one fetching the catalogue is a burst the
 * backend has to absorb for nobody (2026-11 Daleel pulses).
 */
class InAppControllerTest {
    private var fetchesLaunched = 0

    private val recordingScope =
        CoroutineScope(
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    fetchesLaunched += 1
                }
            },
        )

    private fun controller() =
        InAppController(
            clientKey = "pub_test",
            store = ArselStore(FakeSharedPreferences()),
            enqueue = {},
            scope = recordingScope,
            log = ArselLog(LogLevel.NONE),
        )

    @Test
    fun `a background start loads the catalogue without fetching it`() {
        controller().start(refresh = false)

        assertEquals(0, fetchesLaunched)
    }

    @Test
    fun `a foreground start fetches a catalogue it does not have`() {
        controller().start()

        assertEquals(1, fetchesLaunched)
    }
}
