package sa.arsel.core.internal

import android.content.Context
import sa.arsel.core.ArselConfig
import sa.arsel.core.ArselDiagnostics
import sa.arsel.core.BuildConfig
import sa.arsel.core.model.PushEnablementStatus
import sa.arsel.core.notification.NotificationPermission
import sa.arsel.core.notification.Notifications
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore

/**
 * Collects the support snapshot. Every read is individually crash-isolated: a diagnostics call that
 * threw would be worse than useless — it is the one thing an integrator reaches for when the SDK is
 * already misbehaving.
 */
internal object Diagnostics {
    private const val FIREBASE_MESSAGING_CLASS = "com.google.firebase.messaging.FirebaseMessaging"

    fun collect(
        context: Context,
        config: ArselConfig,
        store: ArselStore,
        state: StateManager,
    ): ArselDiagnostics =
        ArselDiagnostics(
            sdkVersion = BuildConfig.SDK_VERSION,
            installationId = runCatching { state.installationId }.getOrNull(),
            anonymousId = runCatching { state.anonymousId }.getOrNull(),
            hasAssertedIdentity = runCatching { state.hasAssertedIdentity() }.getOrDefault(false),
            hasDeviceSecret = store.deviceSecret != null,
            hasPushToken = state.pushToken != null,
            isRegistered = store.lastRegisteredHash != null,
            subscriptionId = store.subscriptionId,
            subscriptionStatus = store.subscriptionStatus,
            lastResponseCode = store.lastResponseCode,
            lastResponsePath = store.lastResponsePath,
            lastResponseAtMs = store.lastResponseAtMs,
            queueDepth = runCatching { store.getRequests().size }.getOrDefault(0),
            eventQueueDepth = runCatching { store.eventQueueDepth() }.getOrDefault(0),
            lastFlushAtMs = store.lastResponseAtMs,
            enablementStatus =
                runCatching {
                    NotificationPermission.currentStatus(context, store.notificationPermissionRequested)
                }.getOrDefault(PushEnablementStatus.DENIED),
            defaultChannelId = config.defaultChannelId,
            channelImportance =
                runCatching {
                    Notifications.channelImportance(context, config.defaultChannelId)
                }.getOrNull(),
            isFirebasePresent = isFirebasePresent(),
        )

    fun isFirebasePresent(): Boolean = runCatching { Class.forName(FIREBASE_MESSAGING_CLASS) }.isSuccess
}
