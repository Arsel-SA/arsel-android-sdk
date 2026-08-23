package sa.arsel.core

import sa.arsel.core.model.PushEnablementStatus

/**
 * A point-in-time snapshot of everything needed to answer "why isn't push arriving on this device?"
 * without attaching a debugger to a customer's handset.
 *
 * Deliberately free of secrets: no FCM token, no device secret, no contact token. Every field here
 * is safe to paste into a support ticket, which is the only way a field diagnostic actually gets
 * used. Not a `data class` — the field set is expected to grow, and `copy()`/`componentN()` are
 * binary API that would break every host compiled against the old shape.
 */
public class ArselDiagnostics internal constructor(
    /** The `core` version, as sent in `X-Arsel-SDK`. */
    public val sdkVersion: String,
    /** The backend's natural key for this device. Null before `initialize()`. */
    public val installationId: String?,
    /** The person-shaped identity events carry before login. Rotated by `reset()`. */
    public val anonymousId: String?,
    /** True once `identify()` has asserted at least one identifier for this user. */
    public val hasAssertedIdentity: Boolean,
    /** Whether the one-time registration secret is held. False means no authed call can succeed. */
    public val hasDeviceSecret: Boolean,
    /** Whether an FCM registration token has been captured. False means nothing can be delivered. */
    public val hasPushToken: Boolean,
    /** True once the backend has confirmed a registration for the current device facts. */
    public val isRegistered: Boolean,
    public val subscriptionId: String?,
    /** Last `status` the backend reported, e.g. `ACTIVE` / `REVOKED`. */
    public val subscriptionStatus: String?,
    /** HTTP status of the most recent backend call; `-1` for no response, `0` if none yet. */
    public val lastResponseCode: Int,
    public val lastResponsePath: String?,
    /** Epoch millis of the most recent backend call, or `0` if none yet. */
    public val lastResponseAtMs: Long,
    /** Requests still waiting to be delivered. A number that only grows is the tell. */
    public val queueDepth: Int,
    /**
     * The events half of [queueDepth]. Broken out because the two fail independently — push
     * can be perfectly healthy while every event bounces off a bad client key.
     */
    public val eventQueueDepth: Int,
    /** Epoch millis of the last drain attempt of any kind, or `0`. Pairs with [eventQueueDepth]. */
    public val lastFlushAtMs: Long,
    /** Exactly what the last state report put on the wire, so a support ticket shows the real fact. */
    public val enablementStatus: PushEnablementStatus,
    public val defaultChannelId: String,
    /**
     * `NotificationChannel.importance` for [defaultChannelId], or null below API 26 / before the
     * channel exists. `0` (`IMPORTANCE_NONE`) is a user-blocked channel: delivered, never shown.
     */
    public val channelImportance: Int?,
    /** Whether `firebase-messaging` is on the classpath. False means the host forgot the dependency. */
    public val isFirebasePresent: Boolean,
) {
    /** Multi-line and copy-paste shaped: this is what a support ticket gets pasted into. */
    override fun toString(): String =
        buildString {
            appendLine("Arsel diagnostics")
            appendLine("  sdkVersion            = $sdkVersion")
            appendLine("  installationId        = ${installationId ?: "<none>"}")
            appendLine("  anonymousId           = ${anonymousId ?: "<none>"}")
            appendLine("  hasAssertedIdentity   = $hasAssertedIdentity")
            appendLine("  hasDeviceSecret       = $hasDeviceSecret")
            appendLine("  hasPushToken          = $hasPushToken")
            appendLine("  isRegistered          = $isRegistered")
            appendLine("  subscriptionId        = ${subscriptionId ?: "<none>"}")
            appendLine("  subscriptionStatus    = ${subscriptionStatus ?: "<none>"}")
            appendLine("  lastResponse          = $lastResponseCode ${lastResponsePath ?: "<none>"}")
            appendLine("  lastResponseAtMs      = $lastResponseAtMs")
            appendLine("  queueDepth            = $queueDepth")
            appendLine("  eventQueueDepth       = $eventQueueDepth")
            appendLine("  lastFlushAtMs         = $lastFlushAtMs")
            appendLine("  enablementStatus      = ${enablementStatus.wire}")
            appendLine("  defaultChannelId      = $defaultChannelId")
            appendLine("  channelImportance     = ${channelImportance ?: "<unknown>"}")
            append("  firebasePresent       = $isFirebasePresent")
        }
}
