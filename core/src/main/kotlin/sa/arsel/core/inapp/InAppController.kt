package sa.arsel.core.inapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.net.ApiClient
import sa.arsel.core.store.ArselStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app messaging: fetch the eligibility catalogue, match triggers locally, enforce the frequency
 * rules, and report what was shown.
 *
 * The division of labour is the whole design. The server answers *which* messages this device may
 * show — resolving audience, consent, campaign window, grants and lifetime caps, none of which a
 * handset can know — and this class answers *when*, so drawing a message costs no network call.
 *
 * Nothing here touches a View; [InAppPresenter] is the only part that does.
 */
internal class InAppController(
    private val clientKey: String,
    private val store: ArselStore,
    private val enqueue: (QueuedRequest) -> Unit,
    private val scope: CoroutineScope,
    private val log: ArselLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val fetching = AtomicBoolean(false)

    private var catalogue: InAppCatalogue? = null
    private var state: MutableMap<String, InAppMessageState> = LinkedHashMap()
    private var sessionStartedAtMs: Long = 0
    private var sessionCounts: MutableMap<String, Int> = LinkedHashMap()
    private var activeMessageId: String? = null
    private var suppressed = false

    /** Set once a display surface exists; absent in a host with no Activity lifecycle. */
    var presenter: ((InAppMessage) -> Unit)? = null

    fun start() {
        synchronized(lock) {
            catalogue = InAppParser.parseCatalogue(store.inAppCatalogue, clock())
            state = readState()
            readSession()
        }
        refresh()
    }

    fun setSuppressed(value: Boolean) {
        synchronized(lock) { suppressed = value }
    }

    /**
     * Every event the SDK enqueues passes through here, exactly once and never on a retry.
     *
     * Session starts arrive as the reserved `arsel.session_start`, which is what "show on app
     * open" keys on — NOT the raw foreground callback, which is rate-limited to once per 30s and
     * would fire for a two-second tab-out.
     */
    fun onEvent(
        name: String,
        properties: Map<String, Any?>,
        timestampMs: Long,
    ) {
        when (name) {
            EVENT_SESSION_START -> {
                onSessionWindow(timestampMs)
                refresh()
                observe(TRIGGER_APP_OPEN, null, emptyMap())
            }
            EVENT_SCREEN_VIEW -> {
                val screen = properties[PROP_SCREEN_NAME]?.toString() ?: return
                observe(TRIGGER_SCREEN_VIEW, screen, stringify(properties))
            }
            else -> {
                // Reserved SDK events other than the two above are bookkeeping, not intent.
                if (name.startsWith(RESERVED_PREFIX)) return
                observe(TRIGGER_CUSTOM_EVENT, name, stringify(properties))
            }
        }
    }

    /** Reserved `arsel_iam_sync` push: refresh only, and never render. */
    fun onSyncRequested() {
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        val now = clock()
        synchronized(lock) {
            val cached = catalogue
            if (!force &&
                cached != null &&
                now - cached.fetchedAtMs < cached.ttlSeconds * MILLIS_PER_SECOND
            ) {
                return
            }
        }
        // Single-flight. The catalogue endpoint is throttled per ORG but not per device, so one
        // handset refetching in a loop can 429 every other device in the organization.
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            try {
                fetchCatalogue()
            } catch (t: Throwable) {
                log.w("in-app catalogue refresh failed", t)
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun fetchCatalogue() {
        val secret = store.deviceSecret
        val baseUrl = store.baseUrl
        // No secret means registration has not completed. Silent by design: an org that has not
        // finished setup is ordinary onboarding, not an error.
        if (secret.isNullOrEmpty() || baseUrl.isNullOrEmpty()) return

        val http = ApiClient(baseUrl, store.networkTimeoutMs, log)
        val headers = HashMap<String, String>(2)
        headers[ApiClient.HEADER_DEVICE_AUTH] = secret
        val known = synchronized(lock) { catalogue?.version }
        if (known != null) headers[HEADER_IF_NONE_MATCH] = "\"$known\""

        val response = http.get(cataloguePath(), headers, authenticated = true)

        // Checked before the result: a 304 classifies as SUCCESS but carries no body, and parsing
        // one would blank the very cache the conditional request exists to preserve.
        if (response.code == ApiClient.HTTP_NOT_MODIFIED) {
            synchronized(lock) {
                catalogue =
                    catalogue?.let {
                        InAppCatalogue(it.version, it.ttlSeconds, clock(), it.messages)
                    }
            }
            return
        }
        if (response.result != ApiClient.Result.SUCCESS) return

        val parsed = InAppParser.parseCatalogue(response.body, clock()) ?: return
        synchronized(lock) {
            catalogue = parsed
            pruneState(parsed, clock())
        }
        store.inAppCatalogue = response.body
        persistState()
    }

    private fun cataloguePath(): String = "/api/v1/orgs/$clientKey/in-app/bundle?installationId=${store.installationId}"

    /**
     * A trigger fired. Never throws — it sits on the caller's event path, and an in-app failure
     * must not take an analytics event down with it.
     */
    fun observe(
        type: String,
        eventName: String?,
        properties: Map<String, String>,
    ) {
        val chosen =
            runCatching { pick(clock(), type, eventName, properties) }
                .onFailure { log.w("in-app trigger evaluation failed", it) }
                .getOrNull() ?: return
        presenter?.invoke(chosen) ?: releaseActive()
    }

    /**
     * The first message in SERVER order that survives every rule.
     *
     * Deliberately no client-side sort: the backend already emits priority-descending, then
     * earliest expiry, then campaign id — exactly the documented precedence. Re-sorting here could
     * only diverge from it, and the divergence would be invisible.
     */
    fun pick(
        now: Long,
        type: String,
        eventName: String?,
        properties: Map<String, String>,
    ): InAppMessage? =
        synchronized(lock) {
            // A trigger arriving while a message is on screen is DROPPED, not queued: a queued
            // message surfaces seconds after the interaction that supposedly caused it.
            if (suppressed || activeMessageId != null) return null
            val candidates = catalogue?.messages ?: return null

            for (message in candidates) {
                if (message.triggerType != type) continue
                // A screen view and a custom event of the same name are distinct on the backend,
                // and collapsing them here would fire screen-scoped messages on every event.
                if (type != TRIGGER_APP_OPEN && message.triggerEventName != eventName) continue
                if (!propertiesMatch(message.triggerProperties, properties)) continue

                val entry = state[message.messageId]
                val expiry = message.expiresAtMs
                if (expiry != null && expiry <= now) {
                    reportExpiryLocked(message)
                    continue
                }
                if ((entry?.shown ?: 0) >= message.maxLifetime) continue
                if ((sessionCounts[message.messageId] ?: 0) >= message.maxPerSession) continue
                if (entry != null &&
                    now - entry.lastShownAtMs < message.minSecondsBetween * MILLIS_PER_SECOND
                ) {
                    continue
                }

                // Reserved at selection time, so a second trigger during a delay window cannot
                // start a competing message. Released by the presenter on close.
                activeMessageId = message.messageId
                return message
            }
            return null
        }

    private fun propertiesMatch(
        want: Map<String, String>,
        got: Map<String, String>,
    ): Boolean {
        if (want.isEmpty()) return true
        return want.all { (key, value) -> got[key] == value }
    }

    fun releaseActive() {
        synchronized(lock) { activeMessageId = null }
    }

    fun recordImpression(
        message: InAppMessage,
        triggerEventName: String?,
    ) {
        val now = clock()
        synchronized(lock) {
            val entry = state[message.messageId]
            state[message.messageId] =
                InAppMessageState(
                    shown = (entry?.shown ?: 0) + 1,
                    lastShownAtMs = now,
                    lastSeenAtMs = entry?.lastSeenAtMs ?: now,
                    expiredReported = entry?.expiredReported ?: false,
                )
            sessionCounts[message.messageId] = (sessionCounts[message.messageId] ?: 0) + 1
        }
        persistState()
        persistSession()
        enqueueBeacon(message, BEACON_IMPRESSION) {
            if (triggerEventName != null) it.put(FIELD_TRIGGER_NAME, triggerEventName)
        }
    }

    fun recordClick(
        message: InAppMessage,
        buttonId: String,
    ) {
        enqueueBeacon(message, BEACON_CLICKED) { it.put(FIELD_BUTTON_ID, buttonId) }
    }

    /**
     * Answers are keyed by `fieldId`, never by a destination.
     *
     * The bundle does not carry `fieldKey` at all, so this SDK could not name where an answer
     * lands even if it wanted to — the server resolves each id against the campaign it stored.
     */
    fun recordSubmit(
        message: InAppMessage,
        submission: Map<String, String>,
    ) {
        if (submission.isEmpty()) return
        enqueueBeacon(message, BEACON_SUBMITTED) { event ->
            val answers = JSONObject()
            for ((fieldId, value) in submission) answers.put(fieldId, value)
            event.put(FIELD_SUBMISSION, answers)
        }
    }

    fun recordDismiss(
        message: InAppMessage,
        visibleSeconds: Long,
    ) {
        enqueueBeacon(message, BEACON_DISMISSED) {
            it.put(FIELD_VISIBLE_SECONDS, visibleSeconds.coerceIn(0, MAX_VISIBLE_SECONDS))
        }
    }

    private fun reportExpiryLocked(message: InAppMessage) {
        val entry = state[message.messageId]
        if (entry?.expiredReported == true) return
        state[message.messageId] =
            InAppMessageState(
                shown = entry?.shown ?: 0,
                lastShownAtMs = entry?.lastShownAtMs ?: 0,
                lastSeenAtMs = entry?.lastSeenAtMs ?: clock(),
                expiredReported = true,
            )
        persistState()
        enqueueBeacon(message, BEACON_EXPIRED) { }
    }

    /**
     * `eventType` is LOWERCASE, and no key outside the DTO may be sent.
     *
     * The endpoint runs `forbidNonWhitelisted` with no per-route override, so an uppercase value or
     * one stray property 400s the whole batch — taking every other beacon in it along.
     */
    private fun enqueueBeacon(
        message: InAppMessage,
        eventType: String,
        extra: (JSONObject) -> Unit,
    ) {
        val event =
            JSONObject()
                .put("messageId", message.messageId)
                .put("campaignId", message.campaignId)
                .put("eventType", eventType)
                // Stamped when it HAPPENED, not at drain: a beacon that waits out an offline spell
                // would otherwise land in the wrong hour bucket.
                .put("timestamp", InAppParser.isoTimestamp(clock()))
                .put("variantKey", message.variantKey)
        extra(event)

        val body =
            JSONObject()
                .put("installationId", store.installationId)
                .put("events", JSONArray().put(event))
                .toString()

        enqueue(
            QueuedRequest(
                id = UUID.randomUUID().toString(),
                path = "/api/v1/orgs/$clientKey/in-app/events",
                body = body,
                // Null, matching events: two impressions of the same message are distinct facts,
                // and a dedupe key here would let one evict the other before it was ever sent.
                dedupeKey = null,
                authMode = AuthMode.DEVICE,
                createdAtMs = clock(),
            ),
        )
    }

    /**
     * Prune on age, never on catalogue membership.
     *
     * The catalogue is truncated server-side, so absence is not death: pruning on membership would
     * reset the lifetime counters of a message pushed past the cap by a higher-priority campaign,
     * and it would show all over again.
     */
    private fun pruneState(
        current: InAppCatalogue,
        now: Long,
    ) {
        val present = current.messages.mapTo(HashSet()) { it.messageId }
        val next = LinkedHashMap<String, InAppMessageState>(state.size)
        for ((id, entry) in state) {
            val lastSeen = if (id in present) now else entry.lastSeenAtMs
            if (now - lastSeen > STATE_TTL_MS) continue
            next[id] =
                InAppMessageState(entry.shown, entry.lastShownAtMs, lastSeen, entry.expiredReported)
        }
        state = next
    }

    private fun onSessionWindow(startedAtMs: Long) {
        synchronized(lock) {
            if (sessionStartedAtMs == startedAtMs) return
            sessionStartedAtMs = startedAtMs
            sessionCounts = LinkedHashMap()
        }
        persistSession()
    }

    private fun stringify(properties: Map<String, Any?>): Map<String, String> {
        if (properties.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(properties.size)
        for ((key, value) in properties) {
            if (value != null) out[key] = value.toString()
        }
        return out
    }

    private fun readState(): MutableMap<String, InAppMessageState> {
        val out = LinkedHashMap<String, InAppMessageState>()
        val root = runCatching { JSONObject(store.inAppState ?: return out) }.getOrNull() ?: return out
        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: continue
            out[key] =
                InAppMessageState(
                    shown = entry.optInt("shown"),
                    lastShownAtMs = entry.optLong("lastShownAtMs"),
                    lastSeenAtMs = entry.optLong("lastSeenAtMs"),
                    expiredReported = entry.optBoolean("expiredReported"),
                )
        }
        return out
    }

    private fun persistState() {
        val root = JSONObject()
        synchronized(lock) {
            for ((id, entry) in state) {
                root.put(
                    id,
                    JSONObject()
                        .put("shown", entry.shown)
                        .put("lastShownAtMs", entry.lastShownAtMs)
                        .put("lastSeenAtMs", entry.lastSeenAtMs)
                        .put("expiredReported", entry.expiredReported),
                )
            }
        }
        store.inAppState = root.toString()
    }

    private fun readSession() {
        val root = runCatching { JSONObject(store.inAppSession ?: return) }.getOrNull() ?: return
        sessionStartedAtMs = root.optLong("startedAt")
        val counts = root.optJSONObject("counts") ?: return
        for (key in counts.keys()) sessionCounts[key] = counts.optInt(key)
    }

    private fun persistSession() {
        val counts = JSONObject()
        val root = JSONObject()
        synchronized(lock) {
            for ((id, value) in sessionCounts) counts.put(id, value)
            root.put("startedAt", sessionStartedAtMs)
        }
        root.put("counts", counts)
        store.inAppSession = root.toString()
    }

    private companion object {
        /** Reserved event names the SDK itself emits; only these two carry trigger meaning. */
        const val EVENT_SESSION_START = "arsel.session_start"
        const val EVENT_SCREEN_VIEW = "arsel.screen_view"
        const val RESERVED_PREFIX = "arsel."
        const val PROP_SCREEN_NAME = "screen_name"
        const val HEADER_IF_NONE_MATCH = "If-None-Match"
        const val FIELD_BUTTON_ID = "buttonId"
        const val FIELD_VISIBLE_SECONDS = "visibleSeconds"
        const val FIELD_TRIGGER_NAME = "triggerEventName"
        const val FIELD_SUBMISSION = "submission"
        const val MILLIS_PER_SECOND = 1000L
        const val STATE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_VISIBLE_SECONDS = 86_400L
    }
}
