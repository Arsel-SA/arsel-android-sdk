package sa.arsel.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.model.DeviceSnapshot
import sa.arsel.core.model.PushEnablementStatus
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences

/**
 * The device fingerprint and the identity lifecycle.
 *
 * The fingerprint decides whether anything is reported at all, so a field missing from it is a
 * change the backend never hears about — silently, and for the life of the install.
 */
class StateManagerTest {
    private lateinit var store: ArselStore
    private lateinit var state: StateManager

    @Before
    fun setUp() {
        store = ArselStore(FakeSharedPreferences())
        state = StateManager(store)
        state.pushToken = DEVICE_TOKEN
    }

    // --- device fingerprint ---------------------------------------------------

    @Test
    fun `the registration hash covers every field the backend is told about`() {
        val baseline = state.currentDeviceHash(DEVICE)

        val mutations =
            mapOf(
                "enablementStatus" to DEVICE.copy(enablementStatus = PushEnablementStatus.DENIED),
                "appVersion" to DEVICE.copy(appVersion = "1.4.3"),
                "osVersion" to DEVICE.copy(osVersion = "15"),
                "deviceModel" to DEVICE.copy(deviceModel = "Pixel 9"),
                "deviceManufacturer" to DEVICE.copy(deviceManufacturer = "Samsung"),
                "deviceTimezone" to DEVICE.copy(deviceTimezone = "Africa/Cairo"),
                "deviceLocale" to DEVICE.copy(deviceLocale = "en-GB"),
            )

        mutations.forEach { (field, mutated) ->
            assertNotEquals(
                "a change to $field must be reportable",
                baseline,
                state.currentDeviceHash(mutated),
            )
        }
    }

    @Test
    fun `a token rotation changes the hash`() {
        val baseline = state.currentDeviceHash(DEVICE)
        state.pushToken = "rotated-token"

        assertNotEquals(baseline, state.currentDeviceHash(DEVICE))
    }

    @Test
    fun `the hash is stable for an unchanged device`() {
        assertEquals(state.currentDeviceHash(DEVICE), state.currentDeviceHash(DEVICE.copy()))
    }

    @Test
    fun `field values cannot be shuffled between fields without changing the hash`() {
        // The canonical form is separator-joined, so two adjacent fields whose values swap must not
        // collide — otherwise a locale change that happens to look like a timezone goes unreported.
        val shuffled = DEVICE.copy(deviceTimezone = "ar-SA", deviceLocale = "Asia/Riyadh")

        assertNotEquals(state.currentDeviceHash(DEVICE), state.currentDeviceHash(shuffled))
    }

    // --- "is this worth reporting?" ------------------------------------------

    @Test
    fun `nothing is considered reported before a token exists`() {
        state.pushToken = null
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        assertFalse(state.hasReportedCurrentState(DEVICE))
    }

    @Test
    fun `an unchanged device is not re-reported`() {
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        assertTrue(state.hasReportedCurrentState(DEVICE))
    }

    @Test
    fun `a permission flip is reported even though nothing else changed`() {
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        assertFalse(state.hasReportedCurrentState(DEVICE.copy(enablementStatus = PushEnablementStatus.DENIED)))
    }

    // --- asserted identity ----------------------------------------------------

    @Test
    fun `no identity is asserted before the host calls identify`() {
        assertFalse(state.hasAssertedIdentity())
    }

    @Test
    fun `any one identifier counts as an asserted identity`() {
        state.setAssertedIdentity(null, "a@example.com", null)

        assertTrue(state.hasAssertedIdentity())
    }

    @Test
    fun `a changed externalId drops the previous user's email and phone`() {
        // identify(A, a@x) then identify(B): a@x belonged to A, and carrying it forward would
        // cross-contaminate B's contact with A's identifiers server-side.
        state.setAssertedIdentity("u_A", "a@example.com", "+966512345678")

        state.setAssertedIdentity("u_B", null, null)

        assertEquals("u_B", store.externalId)
        assertNull(store.identifiedEmail)
        assertNull(store.identifiedPhone)
    }

    @Test
    fun `a changed externalId keeps identifiers asserted in the same call`() {
        state.setAssertedIdentity("u_A", "a@example.com", null)

        state.setAssertedIdentity("u_B", "b@example.com", null)

        assertEquals("u_B", store.externalId)
        assertEquals("b@example.com", store.identifiedEmail)
    }

    @Test
    fun `re-asserting the same externalId merges rather than clears`() {
        state.setAssertedIdentity("u_A", "a@example.com", null)

        state.setAssertedIdentity("u_A", null, "+966512345678")

        assertEquals("u_A", store.externalId)
        assertEquals("a@example.com", store.identifiedEmail)
        assertEquals("+966512345678", store.identifiedPhone)
    }

    // --- logout vs opt-out ----------------------------------------------------

    @Test
    fun `logout forgets the contact but leaves the device registered and receiving`() {
        val installationId = state.installationId
        val anonymousId = state.anonymousId
        state.deviceSecret = "dsk_secret"
        state.setAssertedIdentity("u_A", "a@example.com", "+966512345678")
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        state.clearForLogout()

        // Identity is gone, and the anonymous id rotates so the next session is a new person…
        assertFalse(state.hasAssertedIdentity())
        assertNotEquals(anonymousId, state.anonymousId)
        // …but the installation, its one-time secret and its FCM token are not. A logout that
        // dropped the secret would leave the handset unable to unsubscribe or engagement ever again.
        assertEquals(installationId, state.installationId)
        assertEquals("dsk_secret", state.deviceSecret)
        assertEquals(DEVICE_TOKEN, state.pushToken)
    }

    @Test
    fun `logout clears the registration hash so the rotated anonymous id is re-reported`() {
        // The new anonymous id only reaches the backend on a registration, and registration is
        // skipped for an unchanged device — so a logout that kept the hash would leave the
        // subscription bound to the previous person's contact.
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        state.clearForLogout()

        assertFalse(state.hasReportedCurrentState(DEVICE))
    }

    @Test
    fun `a device with no secret yet must register`() {
        assertTrue(state.needsRegistration())
    }

    @Test
    fun `a registered device reports changes through state`() {
        state.deviceSecret = "dsk_secret"
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        assertFalse(state.needsRegistration())
    }

    @Test
    fun `a logged-out device re-registers instead of reporting state`() {
        // A state report carries no anonymous id, so it would leave the subscription bound to the
        // previous person's contact — the device would keep receiving their pushes.
        state.deviceSecret = "dsk_secret"
        store.lastRegisteredHash = state.currentDeviceHash(DEVICE)

        state.clearForLogout()

        assertTrue(state.needsRegistration())
    }

    @Test
    fun `a full reset yields a brand-new installation on the next registration`() {
        val installationId = state.installationId
        state.deviceSecret = "dsk_secret"

        state.clearAll()

        assertNotEquals(installationId, state.installationId)
        assertNull(state.deviceSecret)
        assertNull(state.pushToken)
    }

    // --- helpers --------------------------------------------------------------

    private fun DeviceSnapshot.copy(
        appVersion: String? = this.appVersion,
        osVersion: String? = this.osVersion,
        deviceModel: String? = this.deviceModel,
        deviceManufacturer: String? = this.deviceManufacturer,
        deviceTimezone: String? = this.deviceTimezone,
        deviceLocale: String? = this.deviceLocale,
        enablementStatus: PushEnablementStatus = this.enablementStatus,
    ) = DeviceSnapshot(
        appVersion = appVersion,
        osVersion = osVersion,
        deviceModel = deviceModel,
        deviceManufacturer = deviceManufacturer,
        deviceTimezone = deviceTimezone,
        deviceLocale = deviceLocale,
        enablementStatus = enablementStatus,
    )

    private companion object {
        const val DEVICE_TOKEN = "fcm-registration-token"

        val DEVICE =
            DeviceSnapshot(
                appVersion = "1.4.2",
                osVersion = "14",
                deviceModel = "Pixel 8",
                deviceManufacturer = "Google",
                deviceTimezone = "Asia/Riyadh",
                deviceLocale = "ar-SA",
                enablementStatus = PushEnablementStatus.AUTHORIZED,
            )
    }
}
