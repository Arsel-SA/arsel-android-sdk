package sa.arsel.core.internal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import sa.arsel.core.ArselConfig
import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.DeviceInfo
import sa.arsel.core.model.DeviceSnapshot
import sa.arsel.core.model.EngagementRecord
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.net.RequestQueue
import sa.arsel.core.state.StateManager
import java.util.UUID

/**
 * Builds and enqueues the public device calls. Every path is keyed by the opaque
 * [ArselConfig.clientKey] (never a raw orgId) and every device is identified by its
 * `installationId` — the backend's natural key, stable across FCM token rotations.
 *
 * Nothing here writes registration bookkeeping: `commitHash` rides along on the
 * [QueuedRequest] and are applied by the drain only against a confirmed 2xx.
 */
internal class PushController(
    private val context: Context,
    private val config: ArselConfig,
    private val state: StateManager,
    private val queue: RequestQueue,
    private val scope: CoroutineScope,
    private val log: ArselLog,
) {
    /** Serialises registration so two triggers cannot each burn a single-use contact token. */
    private val registrationMutex = Mutex()

    private val engagements = EngagementBatcher(scope, log, ::postEngagements)

    private fun base(): String = "/api/v1/orgs/${config.clientKey}/push"

    /**
     * Report whatever has changed since the backend last confirmed this device: a token rotation, a
     * permission flip, a new locale or app version. Cheap and idempotent — safe on every foreground.
     */
    fun syncDeviceState() {
        launchGuarded("device state sync") {
            if (state.pushToken == null) return@launchGuarded
            val device = DeviceInfo.collect(context, state.notificationPermissionRequested)
            // Anything at all before a device secret exists must go via register, since register
            // is what mints it.
            if (state.deviceSecret == null) {
                register(force = false)
                return@launchGuarded
            }
            if (state.hasReportedCurrentState(device)) return@launchGuarded
            enqueueStateReport(device)
        }
    }

    /** Register unconditionally. */
    fun registerNow() {
        launchGuarded("registration") { register(force = true) }
    }

    /** Durable, non-resurrectable opt-out. */
    fun unsubscribe() {
        engagements.flushNow()
        queue.enqueue(
            request(
                "${base()}/subscriptions/unsubscribe",
                PushBodies.unsubscribe(state.installationId),
                dedupeKey = DEDUPE_UNSUBSCRIBE,
                authMode = AuthMode.DEVICE,
            ),
        )
    }

    fun engagement(record: EngagementRecord) {
        engagements.add(record)
    }

    /**
     * Push everything coalescing or queued at the backend now, rather than on the batcher's window
     * and WorkManager's own schedule. For QA harnesses and for an integrator verifying setup — the
     * normal path needs no prompting.
     */
    fun flushNow() {
        engagements.flushNow()
        queue.scheduleDrain()
    }

    // --- registration ---

    private suspend fun register(force: Boolean) =
        registrationMutex.withLock {
            val deviceToken = state.pushToken ?: return@withLock
            val device = DeviceInfo.collect(context, state.notificationPermissionRequested)
            if (!force && state.hasReportedCurrentState(device)) return@withLock

            // Registration is the one call that cannot present a device secret: it is what mints it.
            queue.enqueue(
                request(
                    "${base()}/subscriptions",
                    PushBodies.register(
                        state.installationId,
                        deviceToken,
                        state.anonymousId,
                        device,
                    ),
                    dedupeKey = DEDUPE_REGISTER,
                    authMode = AuthMode.NONE,
                    commitHash = state.currentDeviceHash(device),
                ),
            )
            log.d("register enqueued")
        }

    // --- device state ---

    private fun enqueueStateReport(device: DeviceSnapshot) {
        val deviceToken = state.pushToken ?: return
        queue.enqueue(
            request(
                "${base()}/subscriptions/state",
                PushBodies.state(state.installationId, deviceToken, device),
                dedupeKey = DEDUPE_STATE,
                authMode = AuthMode.DEVICE,
                commitHash = state.currentDeviceHash(device),
            ),
        )
        log.d("device state report enqueued")
    }

    // --- engagements ---

    private fun postEngagements(records: List<EngagementRecord>) {
        val body = PushBodies.engagements(state.installationId, records)

        // Enqueued on the calling thread, which for a tap or a dismissal is the main one. That is
        // the deliberate trade: the write is a single SharedPreferences commit, and handing it to a
        // background dispatcher would race the death of the trampoline Activity or the broadcast
        // receiver that raised the signal — losing the very events with no second chance to observe.
        //
        // No dedupe key: batches accumulate, and a shared key would let a later batch evict an
        // earlier one that is still holding the only copy of those signals.
        queue.enqueue(
            request("${base()}/engagements", body, dedupeKey = null, authMode = AuthMode.DEVICE),
        )
    }

    // --- plumbing ---

    private fun launchGuarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching { block() }.onFailure { log.w("$what failed", it) }
        }
    }

    private fun request(
        path: String,
        body: JSONObject,
        dedupeKey: String?,
        authMode: AuthMode,
        commitHash: String? = null,
    ) = QueuedRequest(
        id = UUID.randomUUID().toString(),
        path = path,
        body = body.toString(),
        dedupeKey = dedupeKey,
        authMode = authMode,
        createdAtMs = System.currentTimeMillis(),
        commitHash = commitHash,
    )

    private companion object {
        const val DEDUPE_REGISTER = "register"
        const val DEDUPE_UNSUBSCRIBE = "unsubscribe"
        const val DEDUPE_STATE = "state"
    }
}
