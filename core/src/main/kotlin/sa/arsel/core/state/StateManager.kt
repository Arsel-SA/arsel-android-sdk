package sa.arsel.core.state

import sa.arsel.core.model.DeviceSnapshot
import sa.arsel.core.store.ArselStore
import java.security.MessageDigest

/**
 * In-memory device profile, write-through to [ArselStore]. Holds the installation identity, the
 * device secret, the FCM token, the pending contact token, and the last-confirmed device
 * fingerprint so an unchanged device is never re-reported.
 */
internal class StateManager(private val store: ArselStore) {
    val installationId: String get() = store.installationId

    /** Person-shaped and rotated on logout — see [ArselStore.anonymousId]. */
    val anonymousId: String get() = store.anonymousId

    /**
     * Client-asserted identity from [sa.arsel.core.Arsel.identify]. Persisted because every
     * subsequent event has to carry it, or the backend would resolve them back to the anonymous
     * contact.
     *
     * A call that changes [externalId] to a *different* value is a different person: the stored
     * email and phone belonged to the previous identity, and carrying them forward would merge two
     * users' identifiers onto one contact server-side. They are dropped unless re-asserted.
     */
    fun setAssertedIdentity(
        externalId: String?,
        email: String?,
        phoneNumber: String?,
    ) {
        val previous = store.externalId
        if (externalId != null && previous != null && externalId != previous) {
            store.identifiedEmail = null
            store.identifiedPhone = null
        }
        externalId?.let { store.externalId = it }
        email?.let { store.identifiedEmail = it }
        phoneNumber?.let { store.identifiedPhone = it }
    }

    fun hasAssertedIdentity(): Boolean = store.externalId != null || store.identifiedEmail != null || store.identifiedPhone != null

    /** Whether the POST_NOTIFICATIONS prompt has ever been launched — see [ArselStore]. */
    var notificationPermissionRequested: Boolean
        get() = store.notificationPermissionRequested
        set(value) {
            store.notificationPermissionRequested = value
        }

    /**
     * Read through to the store rather than cached: [sa.arsel.core.net.PushSyncWorker] mints
     * it on the drain thread and clears it on a rejected auth, so an in-memory copy here would go
     * stale against the value that is actually sent.
     */
    var deviceSecret: String?
        get() = store.deviceSecret
        set(value) {
            store.deviceSecret = value
        }

    @Volatile
    var pushToken: String? = store.pushToken
        set(value) {
            field = value
            store.pushToken = value
        }

    /**
     * Fingerprint of the device facts the backend currently knows about.
     *
     * Covers every field the subscription row stores — a hash over just the token would let a
     * changed locale, timezone, app version or permission state sit unreported forever.
     */
    fun currentDeviceHash(device: DeviceSnapshot): String {
        val canonical =
            listOf(
                pushToken.orEmpty(),
                device.enablementStatus.wire,
                device.appVersion.orEmpty(),
                device.osVersion.orEmpty(),
                device.deviceModel.orEmpty(),
                device.deviceManufacturer.orEmpty(),
                device.deviceTimezone.orEmpty(),
                device.deviceLocale.orEmpty(),
            ).joinToString(FIELD_SEPARATOR)
        return sha256Hex(canonical)
    }

    fun hasReportedCurrentState(device: DeviceSnapshot): Boolean =
        pushToken != null && store.lastRegisteredHash == currentDeviceHash(device)

    /**
     * Whether the next report must be a registration rather than a state report. Only registration
     * carries the anonymous id that binds the subscription to a contact, and [clearForLogout] drops
     * the hash — so a logged-out device reported through state would stay bound to the previous
     * person and keep receiving their pushes.
     */
    fun needsRegistration(): Boolean = deviceSecret == null || store.lastRegisteredHash == null

    /**
     * Logout. Drops who the device belonged to but keeps the installation id and device secret:
     * the installation still exists on the backend, and the secret is issued exactly once — a
     * logout that forgot it would leave the device unable to unsubscribe or engagement ever again.
     */
    fun clearForLogout() {
        store.clearIdentity()
    }

    /** Full reset: a brand-new installation identity on the next registration. */
    fun clearAll() {
        pushToken = null
        store.clearAll()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val FIELD_SEPARATOR = "|"
    }
}
