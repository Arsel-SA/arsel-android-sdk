package sa.arsel.core.internal

import sa.arsel.core.store.ArselStore

/** Emits `arsel.app_installed` once in the life of an install. */
internal class InstallTracker(
    private val store: ArselStore,
    private val events: EventController,
    private val appVersion: () -> String?,
    private val sdkVersion: String,
) {
    /**
     * @param alreadyInstalled captured by the caller, because the identity accessors mint on first
     *   read: once anything has touched one, a first install and an SDK upgrade look identical.
     */
    fun reportIfNew(alreadyInstalled: Boolean) {
        if (store.installReported) return

        // Before the emit, not after: a crash in between costs one install event, where the other
        // order costs a duplicate on every relaunch until one lands.
        store.installReported = true
        if (alreadyInstalled) return

        events.trackReserved(
            EventBodies.EVENT_APP_INSTALLED,
            properties =
                mapOf(
                    PROP_APP_VERSION to appVersion(),
                    PROP_SDK_VERSION to sdkVersion,
                    PROP_PLATFORM to PLATFORM_ANDROID,
                ),
        )
    }

    internal companion object {
        const val PROP_APP_VERSION = "app_version"
        const val PROP_SDK_VERSION = "sdk_version"
        const val PROP_PLATFORM = "platform"
        const val PLATFORM_ANDROID = "android"
    }
}
