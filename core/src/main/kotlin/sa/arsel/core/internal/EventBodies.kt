package sa.arsel.core.internal

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure construction of the `POST /v1/events/send` body.
 *
 * Kept apart from [PushBodies] because it is a different subsystem entirely: a different route, a
 * different credential (the publishable client key, not the device secret) and a different backend
 * DTO. Every field name below is a `class-validator` property on `IngestEventDto`, and
 * `forbidNonWhitelisted` turns a stray one into a 400 that loses the event.
 *
 * Free of [android.content.Context] so the JVM unit tests can assert the shape field by field.
 */
internal object EventBodies {
    /** Reserved prefix for SDK-emitted events, so a customer's own names can never collide. */
    const val RESERVED_PREFIX = "arsel."

    const val EVENT_SESSION_START = "${RESERVED_PREFIX}session_start"
    const val EVENT_SESSION_END = "${RESERVED_PREFIX}session_end"
    const val EVENT_IDENTIFY = "${RESERVED_PREFIX}identify"
    const val EVENT_APP_INSTALLED = "${RESERVED_PREFIX}app_installed"

    /** `IngestEventDto`. Length caps mirror the DTO's `@Length`, applied here so the 400 never happens. */
    const val MAX_EVENT_NAME = 80
    const val MAX_ANONYMOUS_ID = 128
    const val MAX_EXTERNAL_ID = 255

    /**
     * @param anonymousId always sent. It is the identity that exists before anyone logs in, and
     *   keeping it on identified events too is what lets the backend merge the two.
     */
    fun event(
        name: String,
        properties: Map<String, Any?>,
        anonymousId: String,
        externalId: String?,
        email: String?,
        phoneNumber: String?,
        timestampMs: Long,
    ): JSONObject {
        val body =
            JSONObject()
                .put(FIELD_EVENT, name.take(MAX_EVENT_NAME))
                .put(FIELD_ANONYMOUS_ID, anonymousId.take(MAX_ANONYMOUS_ID))
                .put(FIELD_DATA, properties.toJson())
                .put(FIELD_TIMESTAMP, iso8601(timestampMs))

        // put(String, Any?) removes the key on null, so an unset identifier stays absent rather
        // than arriving as a JSON null the DTO would reject.
        return body
            .put(FIELD_EXTERNAL_ID, externalId?.take(MAX_EXTERNAL_ID))
            .put(FIELD_EMAIL, email)
            .put(FIELD_PHONE_NUMBER, phoneNumber)
    }

    /**
     * `data` must be a JSON object. Values the wire cannot carry are stringified rather than
     * dropped: a customer who put a Date in a property is better served by a readable string in
     * their event than by a field that silently vanished.
     */
    private fun Map<String, Any?>.toJson(): JSONObject {
        val json = JSONObject()
        for ((key, value) in this) {
            if (key.isBlank()) continue
            when (value) {
                null -> Unit
                is String, is Boolean, is Int, is Long, is Double, is Float -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        return json
    }

    /**
     * The DTO validates with `@IsISO8601`. Built with an explicit UTC formatter rather than the
     * device's default locale — a Hijri or Thai-Buddhist default calendar would otherwise emit a
     * year the parser rejects, on exactly the handsets this SDK's first customer ships to.
     */
    fun iso8601(timestampMs: Long): String =
        SimpleDateFormat(ISO_8601, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestampMs))

    private const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private const val FIELD_EVENT = "event"
    private const val FIELD_ANONYMOUS_ID = "anonymous_id"
    private const val FIELD_EXTERNAL_ID = "external_id"
    private const val FIELD_EMAIL = "email"
    private const val FIELD_PHONE_NUMBER = "phone_number"
    private const val FIELD_DATA = "data"
    private const val FIELD_TIMESTAMP = "timestamp"
}
