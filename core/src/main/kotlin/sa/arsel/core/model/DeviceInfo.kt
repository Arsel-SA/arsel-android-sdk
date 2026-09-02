package sa.arsel.core.model

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import sa.arsel.core.notification.NotificationPermission
import java.util.Locale
import java.util.TimeZone

/**
 * One reading of the device facts the backend stores on a subscription. Every field is bounded to
 * the backend's column limit here rather than at the call site — an over-long value is a 400 that
 * fails the whole registration, and a truncated `deviceModel` is worth strictly more than none.
 */
internal class DeviceSnapshot(
    val appVersion: String?,
    val osVersion: String?,
    val deviceModel: String?,
    val deviceManufacturer: String?,
    /** IANA zone, e.g. `Asia/Riyadh`. */
    val deviceTimezone: String?,
    /** BCP-47, e.g. `ar-SA`. */
    val deviceLocale: String?,
    val enablementStatus: PushEnablementStatus,
)

internal object DeviceInfo {
    /** Backend `SHORT_TEXT_MAX`. */
    private const val SHORT_TEXT_MAX = 64

    /** Backend `DEVICE_MODEL_MAX`. */
    private const val DEVICE_MODEL_MAX = 128

    fun collect(
        context: Context,
        hasRequestedPermission: Boolean,
    ): DeviceSnapshot =
        DeviceSnapshot(
            appVersion = appVersion(context),
            osVersion = Build.VERSION.RELEASE.bounded(SHORT_TEXT_MAX),
            deviceModel = Build.MODEL.bounded(DEVICE_MODEL_MAX),
            deviceManufacturer = Build.MANUFACTURER.bounded(SHORT_TEXT_MAX),
            deviceTimezone = runCatching { TimeZone.getDefault().id }.getOrNull().bounded(SHORT_TEXT_MAX),
            deviceLocale =
                runCatching { Locale.getDefault().toLanguageTag() }.getOrNull()
                    .bounded(SHORT_TEXT_MAX),
            enablementStatus =
                runCatching {
                    NotificationPermission.currentStatus(context, hasRequestedPermission)
                }.getOrDefault(PushEnablementStatus.DENIED),
        )

    fun appVersion(context: Context): String? =
        runCatching {
            val packageManager = context.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(context.packageName, 0)
                }
            info.versionName
        }.getOrNull().bounded(SHORT_TEXT_MAX)

    private fun String?.bounded(max: Int): String? = this?.takeIf { it.isNotBlank() }?.take(max)
}
