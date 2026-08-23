package sa.arsel.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import sa.arsel.core.internal.Diagnostics
import sa.arsel.core.internal.EventBodies
import sa.arsel.core.internal.Identifiers
import sa.arsel.core.model.ArselPushMessage
import sa.arsel.core.model.EngagementEvent
import sa.arsel.core.model.EngagementRecord
import sa.arsel.core.notification.NotificationPermission
import sa.arsel.core.notification.Notifications
import sa.arsel.core.notification.RenderResult

/**
 * Public entry point for the Arsel SDK. Firebase-free by design — the push-fcm module bridges
 * Firebase to this facade. Every entry point is crash-isolated.
 *
 * Two independent subsystems. **Events and identity work with no push token, no notification permission
 * and no Firebase at all** — a user who declines notifications still has an identity and a
 * behavioural history. Only delivery needs push.
 *
 * Minimal host integration:
 * ```
 * Arsel.initialize(this, ArselConfig.Builder(clientKey, baseUrl).smallIcon(R.drawable.ic).build())
 *
 * Arsel.track("product.viewed", mapOf("sku" to "A-1023"))  // anonymous until identified
 * Arsel.identify(externalId = myUser.id)                   // merges the anonymous history
 *
 * Arsel.reset()     // on logout — new anonymous identity; device keeps receiving push
 * Arsel.optOut()    // only when the USER asks to stop receiving push; not resurrectable
 * ```
 */
public object Arsel {
    private const val TAG = "Arsel"

    /** Reserved push data key: refresh the in-app catalogue and render nothing. */
    private const val KEY_IAM_SYNC = "arsel_iam_sync"

    /** Reserved event name for a screen view; the screen itself travels as a property. */
    private const val EVENT_SCREEN_VIEW = "arsel.screen_view"
    private const val PROP_SCREEN_NAME = "screen_name"

    /** Glue set by the push-fcm module (via App Startup) so [initialize] can fetch the token. */
    public interface FcmBridge {
        public fun requestCurrentToken()
    }

    @JvmStatic
    public var fcmBridge: FcmBridge? = null

    /**
     * Why the SDK refused to start, or null. See [initialize].
     */
    @Volatile
    private var configError: String? = null

    /** Idempotent. Call once from Application.onCreate(). */
    @JvmStatic
    public fun initialize(
        context: Context,
        config: ArselConfig,
    ) {
        // Declines rather than throws. This runs from Application.onCreate, so a config mistake
        // that propagated would take the host app down at launch — for a fault that should cost
        // telemetry, not a session. The reason stays readable via [diagnostics].
        config.validationError()?.let { problem ->
            configError = problem
            Log.e(TAG, "not started — $problem")
            return
        }
        configError = null
        runCatching {
            Registry.init(context, config)
            warnIfFirebaseMissing()
            Notifications.ensureDefaultChannel(Registry.appContext, config) // channel at init, not lazily
            fcmBridge?.requestCurrentToken()
            Registry.controller.syncDeviceState()
        }.onFailure { Log.e(TAG, "initialize failed", it) }
    }

    /**
     * The backend's PRIMARY identity for this **device**, stable across FCM token rotations. Hand
     * it to your own backend to bind a contact server-to-server.
     *
     * Not a person: it survives logout by design, so it must never be used as a user identifier.
     * [getAnonymousId] is the person-shaped one.
     *
     * Null only before [initialize].
     */
    @JvmStatic
    public fun getInstallationId(): String? {
        if (!Registry.initialized) return null
        return runCatching { Registry.state.installationId }
            .onFailure { Log.w(TAG, "installation id unavailable", it) }
            .getOrNull()
    }

    /**
     * Everything needed to diagnose "push isn't arriving on this device", in one snapshot safe to
     * paste into a support ticket — no token, no device secret, no contact token.
     *
     * Null only before [initialize].
     */
    @JvmStatic
    public fun diagnostics(): ArselDiagnostics? {
        // A refused start is exactly when an integrator reaches for this, so it answers with the
        // reason instead of the null that means "you haven't called initialize() yet".
        configError?.let { return Diagnostics.configRefused(it) }
        if (!Registry.initialized) return null
        return runCatching {
            Diagnostics.collect(Registry.appContext, Registry.config, Registry.store, Registry.state)
        }.onFailure { Log.w(TAG, "diagnostics unavailable", it) }.getOrNull()
    }

    /**
     * The person currently using this app, as an id **you** already have. Stable across email and
     * phone changes, and the identifier a migration should key on.
     *
     * Everything tracked before this call under the anonymous identity is merged onto the contact
     * this resolves to — that is the whole point of calling it. Pass only what you have; identifiers
     * are remembered and ride every later event.
     *
     * Prefer [externalId] alone. It binds the contact without shipping the user's email address
     * through the app, and it is the one identifier that does not change under them.
     *
     * This asserts identity from the client. Where the identifier must not be trusted from the
     * device at all, register the device from your own backend instead.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        externalId: String? = null,
        email: String? = null,
        phoneNumber: String? = null,
    ): Unit =
        guarded {
            val id = externalId?.takeIf { it.isNotBlank() }
            // Malformed values are rejected without being stored: a stored bad identifier turns every
            // subsequent event into a permanent 400. Values are not logged — they are PII.
            var mail = email?.takeIf { it.isNotBlank() }
            if (mail != null && !Identifiers.isValidEmail(mail)) {
                Registry.log.e("identify(): email is not a valid address — ignored")
                mail = null
            }
            var phone = phoneNumber?.takeIf { it.isNotBlank() }
            if (phone != null && !Identifiers.isValidPhone(phone)) {
                Registry.log.e("identify(): phoneNumber is not E.164 (e.g. +9665xxxxxxxx) — ignored")
                phone = null
            }
            if (id == null && mail == null && phone == null) {
                Registry.log.w("identify() needs at least one valid externalId, email or phoneNumber")
                return@guarded
            }
            Registry.state.setAssertedIdentity(externalId = id, email = mail, phoneNumber = phone)
            // Sent immediately rather than waiting for the host's next track(): the merge is what the
            // caller asked for, and deferring it would leave the two contacts split until something
            // unrelated happens to fire.
            Registry.events.trackReserved(EventBodies.EVENT_IDENTIFY)
        }

    /**
     * Record something the user did. Delivery is durable: events are persisted and survive process
     * death, offline periods and app kills, draining when connectivity returns.
     *
     * Works with no push token, no notification permission and nobody logged in — an event tracked
     * before [identify] attaches to the anonymous identity and is merged forward later.
     *
     * Names beginning `arsel.` are reserved for the SDK and ignored.
     *
     * @param properties values are sent as-is for strings, numbers and booleans; anything else is
     *   stringified rather than dropped.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
    ): Unit =
        guarded {
            Registry.events.track(name, properties)
        }

    /**
     * Record a screen view.
     *
     * One event, two consumers: it reaches segments and automations exactly as [track] would, and
     * it is the trigger source for screen-scoped in-app messages. Deliberately not a [track] call
     * with a convention name — the backend treats a screen view and a custom event of the same name
     * as different trigger types, and collapsing them would fire screen-scoped messages everywhere.
     */
    @JvmStatic
    @JvmOverloads
    public fun screen(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
    ): Unit =
        guarded {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                Registry.events.trackReserved(
                    EVENT_SCREEN_VIEW,
                    properties + mapOf(PROP_SCREEN_NAME to trimmed),
                )
            }
        }

    /**
     * Hold in-app messages back, or let them through again.
     *
     * For the moments a host knows are wrong — a checkout step, a video playing full screen. Not
     * persisted: it describes the current screen, not the device.
     */
    @JvmStatic
    public fun setInAppMessagingEnabled(enabled: Boolean): Unit =
        guarded {
            Registry.inApp.setSuppressed(!enabled)
        }

    /**
     * The identity events carry before anyone logs in. Rotated by [reset] so a shared handset does
     * not hand the next user the previous one's history.
     *
     * Null only before [initialize].
     */
    @JvmStatic
    public fun getAnonymousId(): String? {
        if (!Registry.initialized) return null
        return runCatching { Registry.state.anonymousId }
            .onFailure { Log.w(TAG, "anonymous id unavailable", it) }
            .getOrNull()
    }

    /**
     * Re-register this device unconditionally, minting a fresh contact token if a provider is set.
     *
     * Not needed in normal operation — registration is driven by [initialize], token rotation and
     * app foreground. It exists for QA harnesses and for an integrator proving their setup works.
     */
    @JvmStatic
    public fun registerNow(): Unit =
        guarded {
            Registry.controller.registerNow()
        }

    /**
     * Send anything coalescing or queued immediately, instead of on the batcher's window and
     * WorkManager's own schedule. Same audience as [registerNow]; delivery still requires network.
     */
    @JvmStatic
    public fun flushNow(): Unit =
        guarded {
            Registry.controller.flushNow()
        }

    /** Usually automatic via ArselPushService.onNewToken; public for hosts with their own service. */
    @JvmStatic
    public fun setPushToken(token: String): Unit =
        guarded {
            Registry.state.pushToken = token
            Registry.controller.syncDeviceState()
        }

    /**
     * Logout. Forgets the contact binding so the next login re-identifies from scratch, and leaves
     * the installation registered and receiving.
     *
     * Also rotates the anonymous identity: events tracked after this belong to whoever picks the
     * handset up next, not to the person who just left.
     *
     * Deliberately NOT an unsubscribe: the backend's opt-out is durable and non-resurrectable, so a
     * logout that called it would permanently kill push on that handset for every future user of it.
     * Use [optOut] for a real opt-out.
     */
    @JvmStatic
    public fun reset(): Unit =
        guarded {
            Registry.state.clearForLogout()
        }

    /**
     * Durable, user-initiated opt-out. The backend will not resurrect this installation on a later
     * registration — re-opting-in is a separate, explicit act.
     */
    @JvmStatic
    public fun optOut(): Unit =
        guarded {
            Registry.controller.unsubscribe()
        }

    /**
     * Render an Arsel push from an FCM data payload and report what happened to it. Returns true if
     * this was an Arsel push (claimed). Called by ArselPushService.onMessageReceived (data map only).
     *
     * The claim is two-phase: PENDING before the render, DONE after it. A render that throws
     * releases the claim, so FCM's redelivery gets another chance — marking the message seen up
     * front would suppress the notification *and* its engagements permanently on a single transient
     * failure.
     */
    @JvmStatic
    public fun handlePushData(
        context: Context,
        data: Map<String, String>,
    ): Boolean {
        if (!Registry.initialized) {
            Log.w(TAG, "handlePushData before initialize() — ignoring")
            return false
        }
        return runCatching {
            // Before fromData, and before everything after it. A sync ping carries no arsel_mid, so
            // fromData would return null and this method would answer false — telling a host that
            // multiplexes its own messaging service the message was not ours. It must also precede
            // claimMessage, which would burn a slot in the capped seen-list keyed on an id that
            // does not exist, and the unconditional DELIVERED below, which would book delivery
            // against a campaign message that was never sent.
            if (!data[KEY_IAM_SYNC].isNullOrBlank()) {
                Registry.inApp.onSyncRequested()
                return@runCatching true
            }
            val msg = ArselPushMessage.fromData(data) ?: return false
            val now = System.currentTimeMillis()
            // Atomic claim: two redeliveries can land on two binder threads at once.
            if (!Registry.store.claimMessage(msg.messageId, now)) return true

            // DELIVERED is "the SDK received and claimed it", which is true from here on regardless
            // of what the OS then does with the notification.
            Registry.controller.engagement(EngagementRecord.of(msg, EngagementEvent.DELIVERED, nowMs = now))

            val result =
                try {
                    Notifications.show(context, Registry.config, msg, Registry.log)
                } catch (t: Throwable) {
                    Registry.store.releaseMessage(msg.messageId, now)
                    throw t
                }
            Registry.store.confirmMessage(msg.messageId, System.currentTimeMillis())
            reportRenderOutcome(msg, result)
            true
        }.getOrElse {
            Registry.log.e("handlePushData failed", it)
            true // we claimed it; fail safe so the host doesn't double-handle
        }
    }

    private fun reportRenderOutcome(
        msg: ArselPushMessage,
        result: RenderResult,
    ) {
        // A skipped render (no visible content) is neither displayed nor suppressed; there is no
        // wire suppressionReason for it, and DELIVERED was already reported on claim.
        if (!result.posted && result.suppressionReason == null) return
        val event = if (result.posted) EngagementEvent.DISPLAYED else EngagementEvent.SUPPRESSED
        Registry.controller.engagement(
            EngagementRecord.of(msg, event, suppressionReason = result.suppressionReason),
        )
        result.suppressionReason?.let { Registry.log.w("notification suppressed (${it.wire})") }
    }

    /** Cheap check for hosts multiplexing one FirebaseMessagingService. */
    @JvmStatic
    public fun isArselData(data: Map<String, String>): Boolean = ArselPushMessage.isArselData(data)

    /**
     * Fire the engagement for a tapped notification (also for self-routing hosts).
     *
     * Exactly one event: OPENED for a body tap, CLICKED for an action button. They are separate
     * metrics, and emitting both on every tap makes the two counters identical by construction.
     */
    @JvmStatic
    public fun handleNotificationOpen(intent: Intent): Unit =
        guarded {
            val messageId = intent.getStringExtra(Notifications.EXTRA_MESSAGE_ID) ?: return@guarded
            val actionId = intent.getStringExtra(Notifications.EXTRA_ACTION_ID)?.takeIf { it.isNotBlank() }
            Registry.controller.engagement(
                EngagementRecord(
                    messageId = messageId,
                    event = if (actionId != null) EngagementEvent.CLICKED else EngagementEvent.OPENED,
                    timestampMs = System.currentTimeMillis(),
                    signature = intent.getStringExtra(Notifications.EXTRA_SIGNATURE),
                    signatureKeyId = intent.getStringExtra(Notifications.EXTRA_SIGNATURE_KEY_ID),
                    actionId = actionId,
                    deepLink = intent.getStringExtra(Notifications.EXTRA_DEEP_LINK),
                    suppressionReason = null,
                ),
            )
        }

    /** Fire DISMISSED for a swiped-away notification (also for self-routing hosts). */
    @JvmStatic
    public fun handleNotificationDismiss(intent: Intent): Unit =
        guarded {
            val messageId = intent.getStringExtra(Notifications.EXTRA_MESSAGE_ID) ?: return@guarded
            Registry.controller.engagement(
                EngagementRecord(
                    messageId = messageId,
                    event = EngagementEvent.DISMISSED,
                    timestampMs = System.currentTimeMillis(),
                    signature = intent.getStringExtra(Notifications.EXTRA_SIGNATURE),
                    signatureKeyId = intent.getStringExtra(Notifications.EXTRA_SIGNATURE_KEY_ID),
                    actionId = null,
                    deepLink = null,
                    suppressionReason = null,
                ),
            )
        }

    /**
     * FCM dropped this device's pending backlog — too many messages queued, or the device was
     * offline past the retention window. Nothing can be recovered, but re-reporting device state
     * distinguishes "the handset is alive and the OEM killed us" from a delivery-side fault.
     */
    @JvmStatic
    public fun handleDeletedMessages(): Unit =
        guarded {
            Registry.log.w("FCM discarded pending messages for this device")
            Registry.controller.syncDeviceState()
        }

    /**
     * Android 13+ runtime permission via a host-registered ActivityResult launcher.
     *
     * Records that the prompt happened before launching it: the OS reports "never asked" and
     * "asked and refused" identically, so without this flag a refusal would keep being reported as
     * NOT_DETERMINED. Marked on launch rather than on the result because the host owns the result
     * callback and may never tell us.
     */
    @JvmStatic
    public fun requestNotificationPermission(launcher: ActivityResultLauncher<String>): Unit =
        guarded {
            Registry.state.notificationPermissionRequested = true
            NotificationPermission.request(Registry.appContext, launcher)
        }

    @JvmStatic
    public fun isInitialized(): Boolean = Registry.initialized

    private inline fun guarded(block: () -> Unit) {
        if (!Registry.initialized) {
            Log.w(TAG, "SDK not initialized — call Arsel.initialize() first")
            return
        }
        runCatching(block).onFailure { Registry.log.e("Arsel op failed", it) }
    }

    private fun warnIfFirebaseMissing() {
        if (Diagnostics.isFirebasePresent()) return
        Registry.log.e(
            "firebase-messaging not on the classpath. Add the Firebase BoM + " +
                "com.google.firebase:firebase-messaging to your app — push will NOT work.",
        )
    }
}
