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
 * @property baseUrl Arsel API base, e.g. https://api.arsel.sa. HTTPS enforced, except for a
 *   loopback backend (`localhost`, `127.0.0.1`) and the emulator's host alias `10.0.2.2`.
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

        /**
         * Never throws. Invalid values are carried through and reported by
         * [Arsel.initialize], which logs them and declines to start — a config mistake must not
         * crash the host app from `Application.onCreate`, which is what a `require` here did.
         * The reason is readable at any time via [Arsel.diagnostics].
         */
        public fun build(): ArselConfig {
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

    /**
     * Nil when the configuration can work. The same four rules the web and iOS SDKs apply, in the
     * same order, so a misconfiguration reads identically on every platform.
     */
    internal fun validationError(): String? {
        if (clientKey.isBlank()) return "clientKey is required (the org's publishable pub_… key)"
        if (!clientKey.startsWith("pub_")) {
            return "clientKey should be the publishable pub_… key — never a secret API key"
        }
        if (!baseUrl.startsWith("https://") && !isLocalHttp(baseUrl)) {
            return "baseUrl must be HTTPS (http is allowed for localhost only) (got '$baseUrl')"
        }
        if (runCatching { java.net.URI(baseUrl) }.isFailure) return "baseUrl is not a valid URL"
        return null
    }

    private companion object {
        /**
         * Plain HTTP against a developer's own machine. `10.0.2.2` is included because it is the
         * only address an Android emulator can reach the host on — without it "run the backend
         * locally" is impossible on Android, which is exactly where every integration starts.
         *
         * The host must be followed by a port, a path or nothing, so `http://localhost.evil.com`
         * does not slip through on a prefix match.
         */
        private val LOCAL_HTTP =
            Regex("""^http://(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?(/.*)?$""")

        fun isLocalHttp(url: String): Boolean = LOCAL_HTTP.matches(url)
    }
}
