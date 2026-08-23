package sa.arsel.core.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import sa.arsel.core.ArselConfig
import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.ArselPushAction
import sa.arsel.core.model.ArselPushMessage
import sa.arsel.core.model.SuppressionReason

/** Channel management + notification rendering. Pure Android/AndroidX — no Firebase types. */
internal object Notifications {
    const val EXTRA_MESSAGE_ID = "arsel_message_id"
    const val EXTRA_DEEP_LINK = "arsel_deep_link"
    const val EXTRA_ACTION_ID = "arsel_action_id"
    const val EXTRA_SIGNATURE = "arsel_signature"
    const val EXTRA_SIGNATURE_KEY_ID = "arsel_signature_key_id"
    const val EXTRA_NOTIFICATION_ID = "arsel_notification_id"

    fun ensureDefaultChannel(
        context: Context,
        config: ArselConfig,
    ) {
        ensureChannel(context, config.defaultChannelId, config.defaultChannelName)
    }

    fun ensureChannel(
        context: Context,
        channelId: String,
        channelName: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // channels are API 26+
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(channelId) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Build + post the notification SYNCHRONOUSLY. Runs on the FCM background thread, so the bounded
     * image fetch here is safe (never the main thread).
     *
     * The blocked check runs *before* the image fetch: there is no point spending the service's
     * remaining seconds downloading a picture for a notification the OS will discard.
     *
     * `MissingPermission` is suppressed because POST_NOTIFICATIONS *is* checked, just one call
     * deeper than lint follows: `blockedReason` returns early via `NotificationPermission.isGranted`
     * before anything is posted.
     */
    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        config: ArselConfig,
        msg: ArselPushMessage,
        log: ArselLog,
    ): RenderResult {
        // A push with neither title nor body would post a blank row in the shade.
        if (msg.title.isNullOrBlank() && msg.body.isNullOrBlank()) {
            log.w("push has no title or body — nothing to post")
            return RenderResult.SKIPPED
        }

        val channelId = resolveChannelId(context, config, msg.channelId, log)

        blockedReason(context, channelId)?.let { return RenderResult.suppressed(it) }

        val notificationId = notificationId(msg)

        // A 0 small-icon resource crashes on post → fall back to the app icon.
        val smallIcon = if (config.smallIconResId != 0) config.smallIconResId else context.applicationInfo.icon

        val builder =
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(smallIcon)
                .setContentTitle(msg.title)
                .setContentText(msg.body)
                .setAutoCancel(true)
                .setContentIntent(tapIntent(context, msg, notificationId, action = null))
                .setDeleteIntent(dismissIntent(context, msg, notificationId))
        config.notificationColor?.let { builder.setColor(it) }

        msg.imageUrl?.let { url ->
            NotificationImage.load(url, log)?.let { bitmap ->
                builder.setLargeIcon(bitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?),
                    )
            }
        }

        // Android renders at most three; sending more silently drops the tail, so cap it here where
        // the truncation is visible instead.
        msg.actions.take(MAX_ACTIONS).forEach { action ->
            builder.addAction(
                NO_ACTION_ICON,
                action.label,
                tapIntent(context, msg, notificationId, action),
            )
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        return RenderResult.POSTED
    }

    /**
     * A campaign channel the app never created is NOT created on the fly: it would appear in
     * Settings under the default channel's name — two identical entries the user can't tell apart.
     * The notification posts on the default channel instead.
     */
    private fun resolveChannelId(
        context: Context,
        config: ArselConfig,
        requested: String?,
        log: ArselLog,
    ): String {
        if (requested != null && requested != config.defaultChannelId) {
            // Below API 26 there are no channels and any id is accepted.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return requested
            if (channelImportance(context, requested) != null) return requested
            log.w("channel '$requested' does not exist — posting on the default channel")
        }
        ensureDefaultChannel(context, config)
        return config.defaultChannelId
    }

    /**
     * Deterministic, so a redelivery of the same message REPLACES the notification instead of
     * stacking a second copy. `String.hashCode` is specified by the Java language, not by the
     * runtime, so the value is stable across processes, app versions and devices.
     */
    fun notificationId(msg: ArselPushMessage): Int = (msg.collapseId ?: msg.messageId).hashCode()

    /** Null when the notification will actually be posted. */
    private fun blockedReason(
        context: Context,
        channelId: String,
    ): SuppressionReason? {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // On 13+ the usual cause is an ungranted POST_NOTIFICATIONS; below that the only way to
            // reach this state is the user switching the app off in system settings.
            val permissionDenied =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !NotificationPermission.isGranted(context)
            return if (permissionDenied) {
                SuppressionReason.PERMISSION_DENIED
            } else {
                SuppressionReason.APP_BLOCKED
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return if (isChannelBlocked(manager, channelId)) SuppressionReason.CHANNEL_BLOCKED else null
    }

    /** Extracted so the API 26 `NotificationChannel` reference is never resolved on an older runtime. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun isChannelBlocked(
        manager: NotificationManagerCompat,
        channelId: String,
    ): Boolean = importanceOf(manager, channelId) == NotificationManager.IMPORTANCE_NONE

    /**
     * Resolved channel importance for diagnostics. Null below API 26 (no channels) or before the
     * channel has been created; `0` is `IMPORTANCE_NONE`, i.e. the user blocked it.
     */
    fun channelImportance(
        context: Context,
        channelId: String,
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return importanceOf(NotificationManagerCompat.from(context), channelId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun importanceOf(
        manager: NotificationManagerCompat,
        channelId: String,
    ): Int? = manager.getNotificationChannel(channelId)?.importance

    /**
     * The tap target for the body (`action == null`) or one action button.
     *
     * The signature travels in the Intent because the engagement raised from the tap has to echo it:
     * the tap fires in a different process lifetime from the render, and nothing else carries the
     * value the backend binds the engagement to its message with.
     */
    private fun tapIntent(
        context: Context,
        msg: ArselPushMessage,
        notificationId: Int,
        action: ArselPushAction?,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationTapActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, msg.messageId)
                putExtra(EXTRA_DEEP_LINK, action?.deepLink ?: msg.deepLink)
                putExtra(EXTRA_SIGNATURE, msg.signature)
                putExtra(EXTRA_SIGNATURE_KEY_ID, msg.keyId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                action?.let { putExtra(EXTRA_ACTION_ID, it.actionId) }
                // Unique data so multiple notifications/actions don't collapse into one PendingIntent.
                data = Uri.parse("arselpush://tap/${msg.messageId}/${action?.actionId ?: BODY_TAP}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        return pendingActivity(context, intent, msg.messageId + (action?.actionId ?: BODY_TAP))
    }

    private fun dismissIntent(
        context: Context,
        msg: ArselPushMessage,
        notificationId: Int,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationDismissReceiver::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, msg.messageId)
                putExtra(EXTRA_SIGNATURE, msg.signature)
                putExtra(EXTRA_SIGNATURE_KEY_ID, msg.keyId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                data = Uri.parse("arselpush://dismiss/${msg.messageId}")
                // Explicit component, so the implicit-broadcast restrictions of API 26+ never apply.
                setPackage(context.packageName)
            }
        return PendingIntent.getBroadcast(context, msg.messageId.hashCode(), intent, pendingFlags())
    }

    private fun pendingActivity(
        context: Context,
        intent: Intent,
        requestKey: String,
    ): PendingIntent = PendingIntent.getActivity(context, requestKey.hashCode(), intent, pendingFlags())

    /** FLAG_IMMUTABLE is mandatory on API 31+. */
    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    private const val MAX_ACTIONS = 3
    private const val BODY_TAP = "open"

    /** Modern Android ignores action icons entirely; 0 is the documented "none". */
    private const val NO_ACTION_ICON = 0
}
