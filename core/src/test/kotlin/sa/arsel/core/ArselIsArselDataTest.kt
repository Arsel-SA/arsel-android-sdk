package sa.arsel.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hosts gate their own FirebaseMessagingService on this, so a false negative drops our pushes. */
class ArselIsArselDataTest {
    @Test
    fun `claims the in-app sync ping, which carries no envelope keys`() {
        assertTrue(Arsel.isArselData(mapOf("arsel_iam_sync" to "1")))
    }

    @Test
    fun `leaves a host's own message alone`() {
        assertFalse(Arsel.isArselData(mapOf("order_id" to "A-1", "arsel_iam_sync" to "")))
    }
}
