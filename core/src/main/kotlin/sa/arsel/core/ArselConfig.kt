package sa.arsel.core

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import sa.arsel.core.log.LogLevel

/**
 * Immutable SDK configuration. Build with [Builder] and pass to [Arsel.initialize].
 *
 * @property clientKey Opaque per-org publishable key, `pub_…` (NOT a raw orgId). Safe to compile
 *   into an APK: it authenticates both the push API and the events API, and grants neither read
 *   access nor anything a secret API key can do.
 * @property baseUrl Arsel API base, e.g. https://api.arsel.sa (HTTPS enforced).
 */
public class ArselConfig private constructor(
    public val clientKey: String,
    public val baseUrl: String,
    public val defaultChannelId: String,
    public val defaultChannelName: String,
    @field:DrawableRes public val smallIconResId: Int,
    @field:ColorInt public val notificationColor: Int?,
    public val logLevel: LogLevel,
    public val networkTimeoutMs: Long,
) {
    public class Builder(
        private val clientKey: String,
        baseUrl: String,
    ) {
        private val baseUrl: String = baseUrl.trimEnd('/')
        private var defaultChannelId: String = "arsel_default"
        private var defaultChannelName: String = "Notifications"
        private var smallIconResId: Int = 0
        private var notificationColor: Int? = null
        private var logLevel: LogLevel = LogLevel.WARN
        private var networkTimeoutMs: Long = 15_000L

        /** Default channel created at [Arsel.initialize]; importance is immutable after creation. */
        public fun defaultChannel(
            id: String,
            name: String,
        ): Builder =
            apply {
                defaultChannelId = id
                defaultChannelName = name
            }

        /** Status-bar small icon. If unset, the SDK falls back to the app icon (a missing icon would crash on post). */
        public fun smallIcon(
            @DrawableRes resId: Int,
        ): Builder = apply { smallIconResId = resId }

        public fun notificationColor(
            @ColorInt color: Int,
        ): Builder = apply { notificationColor = color }

        public fun logLevel(level: LogLevel): Builder = apply { logLevel = level }

        public fun networkTimeoutMs(ms: Long): Builder = apply { networkTimeoutMs = ms }

        public fun build(): ArselConfig {
            require(clientKey.isNotBlank()) { "ArselConfig: clientKey must not be blank" }
            require(baseUrl.startsWith("https://")) {
                "ArselConfig: baseUrl must be HTTPS (got '$baseUrl')"
            }
            return ArselConfig(
                clientKey = clientKey,
                baseUrl = baseUrl,
                defaultChannelId = defaultChannelId,
                defaultChannelName = defaultChannelName,
                smallIconResId = smallIconResId,
                notificationColor = notificationColor,
                logLevel = logLevel,
                networkTimeoutMs = networkTimeoutMs,
            )
        }
    }
}
