package sa.arsel.core.store

import android.content.Context
import android.content.SharedPreferences
import sa.arsel.core.log.LogLevel
import sa.arsel.core.model.QueuedRequest
import java.util.UUID

/**
 * Durable SDK state (SharedPreferences). Holds (a) config persisted so [sa.arsel.core.net.PushSyncWorker]
 * can rebuild the API client after a cold restart, (b) the device identity, (c) the message claim set,
 * and (d) the persisted request queue. Survives process death.
 *
 * `commit()` rather than `apply()` is used for the writes whose loss is unrecoverable — the
 * installation id (the backend's natural key), the device secret (issued exactly once) and the
 * request queue. Everything else can be re-derived on the next registration.
 *
 * The store is constructed over a bare [SharedPreferences] so the JVM unit tests can drive the real
 * queue/claim logic against an in-memory fake — the alternative is Robolectric, which this SDK
 * deliberately does not depend on.
 */
internal class ArselStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val queueLock = Any()
    private val seenLock = Any()
    private val installationLock = Any()
    private val anonymousLock = Any()

    // --- config (persisted for the worker) ---
    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(v) = prefs.edit().putString(KEY_BASE_URL, v).apply()

    var clientKey: String?
        get() = prefs.getString(KEY_CLIENT_KEY, null)
        set(v) = prefs.edit().putString(KEY_CLIENT_KEY, v).apply()

    var networkTimeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT, DEFAULT_TIMEOUT_MS)
        set(v) = prefs.edit().putLong(KEY_TIMEOUT, v).apply()

    /** Persisted so [sa.arsel.core.net.PushSyncWorker]'s logging honours the configured level. */
    var logLevel: LogLevel
        get() =
            prefs.getString(KEY_LOG_LEVEL, null)
                ?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
                ?: LogLevel.WARN
        set(v) = prefs.edit().putString(KEY_LOG_LEVEL, v.name).apply()

    // --- device identity ---

    /**
     * Stable across FCM token rotations and the key the backend registers against.
     *
     * Synchronized because the getter mints on first read: an unguarded read-generate-write races
     * under a burst of binder threads (FCM delivery + a host call on the main thread) and would
     * hand two different ids to two concurrent registrations.
     */
    val installationId: String
        get() =
            synchronized(installationLock) {
                prefs.getString(KEY_INSTALLATION_ID, null)
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(KEY_INSTALLATION_ID, it).commit()
                    }
            }

    /**
     * Person-shaped identity for someone who has not identified themselves yet.
     *
     * Deliberately NOT [installationId]. That one names the handset and is stable for the life of
     * the install; this one names whoever is currently using it and is rotated on logout, so the
     * next user of a shared device does not inherit the previous one's event history.
     *
     * Same minting race as [installationId], guarded the same way.
     */
    val anonymousId: String
        get() =
            synchronized(anonymousLock) {
                prefs.getString(KEY_ANONYMOUS_ID, null)
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(KEY_ANONYMOUS_ID, it).commit()
                    }
            }

    /** Reads without minting, unlike [installationId] and [anonymousId] — the reason it exists. */
    val hasDeviceIdentity: Boolean
        get() =
            prefs.getString(KEY_INSTALLATION_ID, null) != null ||
                prefs.getString(KEY_ANONYMOUS_ID, null) != null

    /**
     * `commit()` because an unflushed write is a duplicate install event next launch. Deliberately
     * absent from [clearAll]: a reset is not a reinstall, and a real one takes the whole file.
     */
    var installReported: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_REPORTED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_INSTALL_REPORTED, v).commit()
        }

    /** Logout. The old value is gone for good — that is the point. */
    fun rotateAnonymousId(): Unit =
        synchronized(anonymousLock) {
            prefs.edit().putString(KEY_ANONYMOUS_ID, UUID.randomUUID().toString()).commit()
        }

    // --- client-asserted identity (rides every event; see Arsel.identify) ---

    var externalId: String?
        get() = prefs.getString(KEY_EXTERNAL_ID, null)
        set(v) = prefs.edit().putString(KEY_EXTERNAL_ID, v).apply()

    var identifiedEmail: String?
        get() = prefs.getString(KEY_IDENTIFIED_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_IDENTIFIED_EMAIL, v).apply()

    var identifiedPhone: String?
        get() = prefs.getString(KEY_IDENTIFIED_PHONE, null)
        set(v) = prefs.edit().putString(KEY_IDENTIFIED_PHONE, v).apply()

    // --- session bookkeeping (see SessionTracker) ---

    /**
     * When the app last went to the background, or `0`. `commit()` because backgrounding is exactly
     * when the process gets killed — an unflushed write is lost in the case this field exists for.
     */
    var backgroundedAtMs: Long
        get() = prefs.getLong(KEY_BACKGROUNDED_AT, 0L)
        set(v) {
            prefs.edit().putLong(KEY_BACKGROUNDED_AT, v).commit()
        }

    /** Start of the session currently open, or `0` when none is. */
    var sessionStartedAtMs: Long
        get() = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)
        set(v) {
            prefs.edit().putLong(KEY_SESSION_STARTED_AT, v).commit()
        }

    // --- in-app messaging ---

    /** The whole cached catalogue as one JSON blob, mirroring how the request queue is stored. */
    var inAppCatalogue: String?
        get() = prefs.getString(KEY_IAM_CATALOGUE, null)
        set(v) {
            prefs.edit().putString(KEY_IAM_CATALOGUE, v).apply()
        }

    /**
     * Per-message lifetime counters, as one JSON blob.
     *
     * Deliberately NOT cleared by [clearIdentity]: it records what this DEVICE has already shown a
     * person, and a logout that reset it would let the next user be shown the same message from
     * zero.
     */
    var inAppState: String?
        get() = prefs.getString(KEY_IAM_STATE, null)
        set(v) {
            prefs.edit().putString(KEY_IAM_STATE, v).apply()
        }

    /** `{ startedAt, counts }` — session-scoped display counts, so a cap survives process death. */
    var inAppSession: String?
        get() = prefs.getString(KEY_IAM_SESSION, null)
        set(v) {
            prefs.edit().putString(KEY_IAM_SESSION, v).apply()
        }

    /** Returned by the backend exactly once, on first registration. Losing it is unrecoverable. */
    var deviceSecret: String?
        get() = prefs.getString(KEY_DEVICE_SECRET, null)
        set(v) {
            prefs.edit()
                .apply { if (v == null) remove(KEY_DEVICE_SECRET) else putString(KEY_DEVICE_SECRET, v) }
                .commit()
        }

    /** Backend-assigned subscription row id, echoed on every registration. Diagnostics only. */
    var subscriptionId: String?
        get() = prefs.getString(KEY_SUBSCRIPTION_ID, null)
        set(v) = prefs.edit().putString(KEY_SUBSCRIPTION_ID, v).apply()

    /** Last `status` the backend reported for this subscription (e.g. `ACTIVE` / `REVOKED`). */
    var subscriptionStatus: String?
        get() = prefs.getString(KEY_SUBSCRIPTION_STATUS, null)
        set(v) = prefs.edit().putString(KEY_SUBSCRIPTION_STATUS, v).apply()

    var pushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_PUSH_TOKEN, v).apply()

    // --- last drain outcome (diagnostics only; never drives behaviour) ---

    /** HTTP status of the most recent backend call; `-1` when there was no status line at all. */
    var lastResponseCode: Int
        get() = prefs.getInt(KEY_LAST_RESPONSE_CODE, 0)
        set(v) = prefs.edit().putInt(KEY_LAST_RESPONSE_CODE, v).apply()

    /** Path of the most recent backend call, so a support log says *which* call failed. */
    var lastResponsePath: String?
        get() = prefs.getString(KEY_LAST_RESPONSE_PATH, null)
        set(v) = prefs.edit().putString(KEY_LAST_RESPONSE_PATH, v).apply()

    var lastResponseAtMs: Long
        get() = prefs.getLong(KEY_LAST_RESPONSE_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_RESPONSE_AT, v).apply()

    /** Fingerprint of the device facts the backend has confirmed. Written only on a 2xx. */
    var lastRegisteredHash: String?
        get() = prefs.getString(KEY_REG_HASH, null)
        set(v) = prefs.edit().putString(KEY_REG_HASH, v).apply()

    /**
     * True once the POST_NOTIFICATIONS prompt has been launched for this install.
     *
     * The only thing separating NOT_DETERMINED from DENIED: the OS reports both as an ungranted
     * permission. Written with `commit()` rather than `apply()` because the prompt hands control to
     * the system UI and the process can be killed behind it — losing this would report a refusal as
     * "never asked" for the rest of the install.
     */
    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, v).commit()
        }

    // --- message claims (persisted "id:ts:state;" list, time-expired then capped) ---

    /**
     * Atomically claims [messageId] for rendering, returning false if it is already claimed.
     *
     * Two-phase: the claim starts PENDING and is only promoted by [confirmMessage]. A PENDING claim
     * that outlives [PENDING_TTL_MS] is a render that died with its process — FCM will redeliver,
     * and refusing that redelivery would drop the notification permanently.
     *
     * `commit()` for the same reason the check exists at all: FCM redelivers across process death,
     * so an unflushed claim is a duplicate notification.
     */
    fun claimMessage(
        messageId: String,
        nowMs: Long,
    ): Boolean =
        synchronized(seenLock) {
            val live = readSeen(nowMs)
            val existing = live.firstOrNull { it.id == messageId }
            val reclaimable =
                existing == null ||
                    (existing.pending && nowMs - existing.atMs > PENDING_TTL_MS)
            if (!reclaimable) return@synchronized false
            writeSeen(live.filterNot { it.id == messageId } + SeenEntry(messageId, nowMs, pending = true))
            true
        }

    /** Promotes a claim to DONE, so it blocks redeliveries for the full [SEEN_TTL_MS]. */
    fun confirmMessage(
        messageId: String,
        nowMs: Long,
    ): Unit =
        synchronized(seenLock) {
            val live = readSeen(nowMs)
            if (live.none { it.id == messageId }) return@synchronized
            writeSeen(live.map { if (it.id == messageId) SeenEntry(it.id, nowMs, pending = false) else it })
        }

    /** Drops a claim outright, so the next redelivery renders instead of being silently swallowed. */
    fun releaseMessage(
        messageId: String,
        nowMs: Long,
    ): Unit =
        synchronized(seenLock) {
            val live = readSeen(nowMs)
            val kept = live.filterNot { it.id == messageId }
            if (kept.size == live.size) return@synchronized
            writeSeen(kept)
        }

    /** Entries older than [SEEN_TTL_MS] are dropped on read; the count cap is only a backstop. */
    private fun readSeen(nowMs: Long): List<SeenEntry> =
        prefs.getString(KEY_SEEN, null)
            ?.split(ENTRY_SEPARATOR)
            ?.mapNotNull(::decodeSeenEntry)
            ?.filter { nowMs - it.atMs < SEEN_TTL_MS }
            ?: emptyList()

    private fun writeSeen(entries: List<SeenEntry>) {
        prefs.edit().putString(KEY_SEEN, encodeSeen(entries.takeLast(MAX_SEEN))).commit()
    }

    /** Parsed right-to-left: a message id may itself contain the field separator, the suffix may not. */
    private fun decodeSeenEntry(entry: String): SeenEntry? {
        val stateSplit = entry.lastIndexOf(FIELD_SEPARATOR)
        if (stateSplit <= 0) return null
        val timeSplit = entry.lastIndexOf(FIELD_SEPARATOR, stateSplit - 1)
        if (timeSplit <= 0) return null
        val timestamp = entry.substring(timeSplit + 1, stateSplit).toLongOrNull() ?: return null
        return SeenEntry(
            id = entry.substring(0, timeSplit),
            atMs = timestamp,
            pending = entry.substring(stateSplit + 1) == STATE_PENDING,
        )
    }

    private fun encodeSeen(entries: List<SeenEntry>): String =
        entries.joinToString(ENTRY_SEPARATOR.toString()) {
            val state = if (it.pending) STATE_PENDING else STATE_DONE
            "${it.id}$FIELD_SEPARATOR${it.atMs}$FIELD_SEPARATOR$state"
        }

    private class SeenEntry(val id: String, val atMs: Long, val pending: Boolean)

    // --- request queue (authority; read-modify-write under lock) ---

    fun addRequest(req: QueuedRequest) {
        synchronized(queueLock) {
            val existing = QueuedRequest.listFromJson(prefs.getString(KEY_QUEUE, null))
            val deduped =
                req.dedupeKey
                    ?.let { key -> existing.filterNot { it.dedupeKey == key } }
                    ?: existing
            val trimmed = if (deduped.size >= MAX_QUEUE) deduped.takeLast(MAX_QUEUE - 1) else deduped
            prefs.edit().putString(KEY_QUEUE, QueuedRequest.listToJson(trimmed + req)).commit()
        }
    }

    fun getRequests(): List<QueuedRequest> =
        synchronized(queueLock) {
            QueuedRequest.listFromJson(prefs.getString(KEY_QUEUE, null))
        }

    /**
     * Events still undelivered. Reported separately from the total because the two fail
     * independently: push can be perfectly healthy while every event is bouncing off a bad client
     * key, and a single combined number hides exactly that.
     */
    fun eventQueueDepth(): Int = getRequests().count { it.path == EVENTS_PATH }

    /**
     * Deletion by id, never a whole-list replace. A drain that wrote back "what remains" would
     * silently discard anything enqueued while it was on the network — deletion commutes with
     * append, replacement does not.
     */
    fun removeRequests(ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(queueLock) {
            val existing = QueuedRequest.listFromJson(prefs.getString(KEY_QUEUE, null))
            val kept = existing.filterNot { ids.contains(it.id) }
            if (kept.size == existing.size) return
            prefs.edit().putString(KEY_QUEUE, QueuedRequest.listToJson(kept)).commit()
        }
    }

    // --- lifecycle ---

    /**
     * Logout: forget who the device belonged to, keep the installation and its secret.
     *
     * Rotates the anonymous id in the same breath. Keeping it would attach the next user of this
     * handset to the previous user's events — the whole reason this identity is person-shaped and
     * not the installation id.
     */
    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_REG_HASH)
            .remove(KEY_EXTERNAL_ID)
            .remove(KEY_IDENTIFIED_EMAIL)
            .remove(KEY_IDENTIFIED_PHONE)
            .remove(KEY_SESSION_STARTED_AT)
            .commit()
        rotateAnonymousId()
    }

    /** Full reset. Drops the installation identity and the queue; keeps the persisted config. */
    fun clearAll() {
        synchronized(queueLock) {
            synchronized(installationLock) {
                prefs.edit()
                    .remove(KEY_INSTALLATION_ID)
                    .remove(KEY_ANONYMOUS_ID)
                    .remove(KEY_EXTERNAL_ID)
                    .remove(KEY_IDENTIFIED_EMAIL)
                    .remove(KEY_IDENTIFIED_PHONE)
                    .remove(KEY_BACKGROUNDED_AT)
                    .remove(KEY_SESSION_STARTED_AT)
                    .remove(KEY_IAM_CATALOGUE)
                    .remove(KEY_IAM_STATE)
                    .remove(KEY_IAM_SESSION)
                    .remove(KEY_DEVICE_SECRET)
                    .remove(KEY_SUBSCRIPTION_ID)
                    .remove(KEY_SUBSCRIPTION_STATUS)
                    .remove(KEY_PUSH_TOKEN)
                    .remove(KEY_REG_HASH)
                    .remove(KEY_QUEUE)
                    .remove(KEY_SEEN)
                    .remove(KEY_LAST_RESPONSE_CODE)
                    .remove(KEY_LAST_RESPONSE_PATH)
                    .remove(KEY_LAST_RESPONSE_AT)
                    .commit()
            }
        }
    }

    internal companion object {
        /**
         * Also the file name (`arsel_push.xml`) excluded by `xml/arsel_push_backup_rules.xml` —
         * changing it here without changing them there silently starts backing the device secret up.
         */
        const val PREFS_NAME = "arsel_push"

        /** The public ingest route. Org-free — the client key in the header carries the tenant. */
        const val EVENTS_PATH = "/v1/events/send"

        const val KEY_BASE_URL = "base_url"
        const val KEY_CLIENT_KEY = "client_key"
        const val KEY_TIMEOUT = "timeout_ms"
        const val KEY_LOG_LEVEL = "log_level"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_ANONYMOUS_ID = "anonymous_id"
        const val KEY_EXTERNAL_ID = "external_id"
        const val KEY_IDENTIFIED_EMAIL = "identified_email"
        const val KEY_IDENTIFIED_PHONE = "identified_phone"
        const val KEY_BACKGROUNDED_AT = "backgrounded_at"
        const val KEY_SESSION_STARTED_AT = "session_started_at"
        const val KEY_IAM_CATALOGUE = "iam_catalogue"
        const val KEY_IAM_STATE = "iam_state"
        const val KEY_IAM_SESSION = "iam_session"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_SUBSCRIPTION_ID = "subscription_id"
        const val KEY_SUBSCRIPTION_STATUS = "subscription_status"
        const val KEY_PUSH_TOKEN = "push_token"
        const val KEY_LAST_RESPONSE_CODE = "last_response_code"
        const val KEY_LAST_RESPONSE_PATH = "last_response_path"
        const val KEY_LAST_RESPONSE_AT = "last_response_at"
        const val KEY_REG_HASH = "reg_hash"
        const val KEY_PERMISSION_REQUESTED = "permission_requested"
        const val KEY_INSTALL_REPORTED = "install_reported"
        const val KEY_QUEUE = "request_queue"
        const val KEY_SEEN = "seen_messages"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_QUEUE = 200
        const val MAX_SEEN = 200

        /** Must exceed FCM's retry horizon, or a redelivery lands after we forgot the message. */
        const val SEEN_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * Generous against the ~20s Android allows inside `onMessageReceived`, tight enough that a
         * killed render is retried on the next redelivery rather than an hour later.
         */
        const val PENDING_TTL_MS = 60L * 1000
        const val ENTRY_SEPARATOR = ';'
        const val FIELD_SEPARATOR = ':'
        const val STATE_PENDING = "p"
        const val STATE_DONE = "d"
    }
}
