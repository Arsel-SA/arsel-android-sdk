package sa.arsel.core.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The in-app wire model and its parser.
 *
 * Every optional field arrives from a Postgres `jsonb` column and is three-state — absent,
 * explicitly null, or present. `JSONObject.optString` collapses the first two into `""`, so every
 * read here goes through [nullableString] instead. A parser that assumed null-or-value would drop
 * messages silently, and silence is the one failure this channel cannot detect from any surface.
 */
internal object InAppParser {
    fun parseCatalogue(
        json: String?,
        nowMs: Long,
    ): InAppCatalogue? {
        val root = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        // The envelope is never key-validated: the backend's global success interceptor spreads
        // `message` and `timestamp` alongside the contract fields.
        val version = nullableString(root, FIELD_VERSION) ?: return null

        val array = root.optJSONArray(FIELD_MESSAGES) ?: JSONArray()
        val messages = ArrayList<InAppMessage>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseMessage(item)?.let(messages::add)
        }

        val ttl = root.optInt(FIELD_TTL, DEFAULT_TTL_SECONDS)
        return InAppCatalogue(
            version = version,
            ttlSeconds = if (ttl > 0) ttl else DEFAULT_TTL_SECONDS,
            fetchedAtMs = nowMs,
            messages = messages,
        )
    }

    fun parseMessage(json: JSONObject): InAppMessage? {
        val campaignId = nullableString(json, "campaignId") ?: return null
        val messageId = nullableString(json, "messageId") ?: return null
        val layout = nullableString(json, "layout") ?: return null
        if (layout !in KNOWN_LAYOUTS) return null

        val content = json.optJSONObject("content") ?: return null
        val headline = nullableString(content, "headline") ?: return null

        val customHtml = parseCustomHtml(json.optJSONObject("customHtml"))
        // Dropped whole, not degraded to a bare headline panel: the author designed markup, and a
        // stray text modal in its place is a worse outcome than the message not appearing.
        if (layout == LAYOUT_CUSTOM_HTML && customHtml == null) return null

        val trigger = json.optJSONObject("trigger") ?: JSONObject()
        val rules = json.optJSONObject("displayRules") ?: JSONObject()

        return InAppMessage(
            campaignId = campaignId,
            messageId = messageId,
            variantKey = nullableString(json, "variantKey") ?: DEFAULT_VARIANT,
            expiresAtMs = parseIso(nullableString(json, "expiresAt")),
            triggerType = nullableString(trigger, "type") ?: TRIGGER_APP_OPEN,
            triggerEventName = nullableString(trigger, "eventName"),
            triggerProperties = parseProperties(trigger.optJSONObject("properties")),
            maxPerSession = rules.optInt("maxPerSession", DEFAULT_MAX_PER_SESSION),
            maxLifetime = rules.optInt("maxLifetime", DEFAULT_MAX_LIFETIME),
            minSecondsBetween = rules.optInt("minSecondsBetween", DEFAULT_COOLDOWN_SECONDS),
            delaySeconds = rules.optInt("delaySeconds", 0),
            layout = layout,
            headline = headline,
            body = nullableString(content, "body").orEmpty(),
            imageUrl = nullableString(content, "imageUrl"),
            backgroundColor = nullableString(content, "backgroundColor"),
            textColor = nullableString(content, "textColor"),
            // Absent means "not suppressed"; only an explicit false hides it.
            showCloseButton = content.optBoolean("showCloseButton", true),
            buttons = parseButtons(json.optJSONArray("buttons")),
            fields = parseFields(json.optJSONArray("fields")),
            customHtml = customHtml,
        )
    }

    /**
     * Null when the declared source carries no payload, which drops the whole message: an empty
     * sandbox still reports a healthy impression, and that is indistinguishable from delivery.
     */
    private fun parseCustomHtml(json: JSONObject?): InAppCustomHtml? {
        if (json == null) return null
        val source = nullableString(json, "source") ?: return null
        if (source != HTML_SOURCE_INLINE && source != HTML_SOURCE_URL) return null

        val html = nullableString(json, "html")
        val url = nullableString(json, "url")
        if (source == HTML_SOURCE_INLINE && html == null) return null
        if (source == HTML_SOURCE_URL && url == null) return null

        return InAppCustomHtml(
            source = source,
            html = html,
            url = url,
            // Absent means OFF. Anything but an explicit true leaves the WebView scriptless.
            allowJavaScript = json.optBoolean("allowJavaScript", false),
            overlayStyle =
                if (nullableString(json, "overlayStyle") == OVERLAY_TRANSPARENT) {
                    OVERLAY_TRANSPARENT
                } else {
                    OVERLAY_DARK
                },
        )
    }

    private fun parseButtons(array: JSONArray?): List<InAppButton> {
        if (array == null) return emptyList()
        val buttons = ArrayList<InAppButton>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val buttonId = nullableString(item, "buttonId") ?: continue
            val label = nullableString(item, "label") ?: continue
            val action = nullableString(item, "action") ?: continue
            buttons.add(
                InAppButton(
                    buttonId = buttonId,
                    label = label,
                    action = action,
                    value = nullableString(item, "value"),
                ),
            )
        }
        return buttons
    }

    /**
     * An unknown field type is dropped rather than guessed at. Rendering one the SDK does not
     * understand as a text box would collect an answer the server then refuses, which reads to
     * the user as the form being broken.
     */
    private fun parseFields(array: JSONArray?): List<InAppField> {
        if (array == null) return emptyList()
        val fields = ArrayList<InAppField>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val fieldId = nullableString(item, "fieldId") ?: continue
            val label = nullableString(item, "label") ?: continue
            val type = nullableString(item, "type") ?: continue
            if (type !in KNOWN_FIELD_TYPES) continue

            val scale = if (item.has("scale") && !item.isNull("scale")) item.optInt("scale") else null
            fields.add(
                InAppField(
                    fieldId = fieldId,
                    type = type,
                    label = label,
                    required = item.optBoolean("required", false),
                    placeholder = nullableString(item, "placeholder"),
                    options = parseFieldOptions(item.optJSONArray("options")),
                    scale = scale,
                ),
            )
        }
        return fields
    }

    private fun parseFieldOptions(array: JSONArray?): List<InAppFieldOption> {
        if (array == null) return emptyList()
        val options = ArrayList<InAppFieldOption>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val label = nullableString(item, "label") ?: continue
            val value = nullableString(item, "value") ?: continue
            options.add(InAppFieldOption(label = label, value = value))
        }
        return options
    }

    /**
     * Predicates are compared as strings on both sides. The backend types them
     * `Record<string, string>` but validates only with `@IsObject()`, so a number or a boolean can
     * legitimately arrive and must not throw here.
     */
    private fun parseProperties(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>(json.length())
        for (key in json.keys()) {
            if (json.isNull(key)) continue
            out[key] = json.get(key).toString()
        }
        return out
    }

    /** Null for absent AND for an explicit JSON null, which `optString` cannot tell apart. */
    private fun nullableString(
        json: JSONObject,
        key: String,
    ): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotEmpty() }
    }

    /**
     * A literal `Z` rather than the `X` pattern: `X` requires API 24 and this SDK ships to 23,
     * where `SimpleDateFormat` throws on the pattern itself — every expiry on Android 6 would have
     * failed to parse. Unparseable means open-ended, because refusing to show a live message is
     * worse than carrying one whose expiry could not be read.
     */
    private fun parseIso(value: String?): Long? {
        if (value.isNullOrEmpty()) return null
        val normalized = value.trim().removeSuffix("Z")
        for (pattern in ISO_PATTERNS) {
            val parsed =
                runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                            isLenient = false
                        }.parse(normalized)
                        ?.time
                }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun isoTimestamp(millis: Long): String =
        SimpleDateFormat(ISO_WITH_MILLIS, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))

    /**
     * Layouts this build can draw.
     *
     * The client half of the server's `IN_APP_SUPPORTED_LAYOUTS`; the two have to be extended
     * together. The server gates on the version this SDK reports, but a build handed a layout
     * missing from this set drops the message silently — which is the failure this channel has
     * no surface to detect.
     */
    private val KNOWN_LAYOUTS =
        setOf(
            LAYOUT_MODAL,
            LAYOUT_BANNER_TOP,
            LAYOUT_BANNER_BOTTOM,
            LAYOUT_FULLSCREEN,
            LAYOUT_IMAGE_ONLY,
            LAYOUT_HALF_INTERSTITIAL,
            LAYOUT_ALERT,
            LAYOUT_FORM,
            LAYOUT_RATING,
            LAYOUT_CUSTOM_HTML,
        )

    /** With and without milliseconds — both are valid ISO-8601 and both appear in practice. */
    private val ISO_PATTERNS =
        listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
        )

    private const val ISO_WITH_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private const val FIELD_VERSION = "bundleVersion"
    private const val FIELD_MESSAGES = "messages"
    private const val FIELD_TTL = "ttlSeconds"
    private const val DEFAULT_TTL_SECONDS = 900
    private const val DEFAULT_MAX_PER_SESSION = 1
    private const val DEFAULT_MAX_LIFETIME = 3
    private const val DEFAULT_COOLDOWN_SECONDS = 86_400
}

/** One renderable in-app message, exactly as the catalogue describes it. */
internal class InAppMessage(
    val campaignId: String,
    val messageId: String,
    val variantKey: String,
    val expiresAtMs: Long?,
    val triggerType: String,
    val triggerEventName: String?,
    val triggerProperties: Map<String, String>,
    val maxPerSession: Int,
    val maxLifetime: Int,
    val minSecondsBetween: Int,
    val delaySeconds: Int,
    val layout: String,
    val headline: String,
    val body: String,
    val imageUrl: String?,
    val backgroundColor: String?,
    val textColor: String?,
    val showCloseButton: Boolean,
    val buttons: List<InAppButton>,
    /** Present only on FORM and RATING. Never carries a destination key. */
    val fields: List<InAppField>,
    /** Present only on CUSTOM_HTML. */
    val customHtml: InAppCustomHtml?,
)

/**
 * Author-supplied markup and the terms it is drawn under.
 *
 * [allowJavaScript] is a capability the renderer withholds, not a request the markup can make: it
 * decides whether the WebView is given script at all, so a creative authored without script cannot
 * turn script on for itself.
 */
internal class InAppCustomHtml(
    val source: String,
    val html: String?,
    val url: String?,
    val allowJavaScript: Boolean,
    val overlayStyle: String,
)

/**
 * A field as the device sees it.
 *
 * `fieldKey` — where the answer is stored — is deliberately absent from the wire, so this SDK
 * cannot name a destination. Answers are reported against [fieldId] and the server resolves the
 * rest against the campaign it holds.
 */
internal class InAppField(
    val fieldId: String,
    val type: String,
    val label: String,
    val required: Boolean,
    val placeholder: String?,
    val options: List<InAppFieldOption>,
    val scale: Int?,
)

internal class InAppFieldOption(
    val label: String,
    val value: String,
)

internal class InAppButton(
    val buttonId: String,
    val label: String,
    val action: String,
    val value: String?,
)

/**
 * The catalogue as fetched. Server order is preserved and never re-sorted: the backend already
 * emits priority-descending, then earliest expiry, then campaign id, and a client-side sort could
 * only ever diverge from that invisibly.
 */
internal class InAppCatalogue(
    val version: String,
    val ttlSeconds: Int,
    val fetchedAtMs: Long,
    val messages: List<InAppMessage>,
)

/** Per-message lifetime counters. Device-scoped, and deliberately survives a logout. */
internal class InAppMessageState(
    val shown: Int,
    val lastShownAtMs: Long,
    val lastSeenAtMs: Long,
    val expiredReported: Boolean,
)

internal const val TRIGGER_APP_OPEN = "APP_OPEN"
internal const val TRIGGER_SCREEN_VIEW = "SCREEN_VIEW"
internal const val TRIGGER_CUSTOM_EVENT = "CUSTOM_EVENT"
internal const val DEFAULT_VARIANT = "default"

internal const val LAYOUT_MODAL = "MODAL"
internal const val LAYOUT_BANNER_TOP = "BANNER_TOP"
internal const val LAYOUT_BANNER_BOTTOM = "BANNER_BOTTOM"
internal const val LAYOUT_FULLSCREEN = "FULLSCREEN"
internal const val LAYOUT_IMAGE_ONLY = "IMAGE_ONLY"
internal const val LAYOUT_HALF_INTERSTITIAL = "HALF_INTERSTITIAL"
internal const val LAYOUT_ALERT = "ALERT"
internal const val LAYOUT_FORM = "FORM"
internal const val LAYOUT_RATING = "RATING"
internal const val LAYOUT_CUSTOM_HTML = "CUSTOM_HTML"

internal const val HTML_SOURCE_INLINE = "INLINE"
internal const val HTML_SOURCE_URL = "URL"
internal const val OVERLAY_TRANSPARENT = "TRANSPARENT"
internal const val OVERLAY_DARK = "DARK"

/** Layouts that collect answers and therefore draw inputs. */
internal val INPUT_LAYOUTS = setOf(LAYOUT_FORM, LAYOUT_RATING)

internal const val FIELD_TEXT = "text"
internal const val FIELD_EMAIL = "email"
internal const val FIELD_TEL = "tel"
internal const val FIELD_DROPDOWN = "dropdown"
internal const val FIELD_RADIO = "radio"
internal const val FIELD_CHECKBOX = "checkbox"
internal const val FIELD_RATING = "rating"

internal val KNOWN_FIELD_TYPES =
    setOf(
        FIELD_TEXT,
        FIELD_EMAIL,
        FIELD_TEL,
        FIELD_DROPDOWN,
        FIELD_RADIO,
        FIELD_CHECKBOX,
        FIELD_RATING,
    )

/** Matches DEFAULT_IN_APP_RATING_SCALE on the server. */
internal const val DEFAULT_RATING_SCALE = 5

internal const val ACTION_DEEP_LINK = "DEEP_LINK"
internal const val ACTION_URL = "URL"
internal const val ACTION_DISMISS = "DISMISS"
internal const val ACTION_CUSTOM_EVENT = "CUSTOM_EVENT"

internal const val BEACON_IMPRESSION = "impression"
internal const val BEACON_CLICKED = "clicked"
internal const val BEACON_DISMISSED = "dismissed"
internal const val BEACON_EXPIRED = "expired"
internal const val BEACON_SUBMITTED = "submitted"
