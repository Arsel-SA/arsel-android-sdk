# Push notifications

Push is the second subsystem. Everything in [Events](events.md) and [Identity](identity.md) works without
any of it.

## Prerequisites

You own Firebase. The SDK wraps it; it does not bundle it.

- A Firebase project with an Android app registered under **your** package name
- `google-services.json` in the app module, and the `com.google.gms.google-services` plugin
- The Firebase BoM + `com.google.firebase:firebase-messaging` in your dependencies
- The Firebase **service account JSON** uploaded to Arsel, so the backend can send on your behalf

> **Keep your existing Firebase project when migrating.** FCM tokens are bound to your sender id. A
> new project invalidates every token you have, and no import can recover them.

## What is merged into your manifest

Nothing to add for the drop-in case:

| From the SDK | For |
| --- | --- |
| `ArselPushService` | receiving messages |
| `NotificationTapActivity` | taps, engagement, deep-link routing |
| `NotificationDismissReceiver` | swipe-aways |
| `POST_NOTIFICATIONS`, `INTERNET` | permissions |

## Channels

The default channel is created at `initialize()`, not lazily — a channel created at first
notification arrives too late to be configured by the user beforehand.

```kotlin
ArselConfig.Builder(clientKey, baseUrl)
    .defaultChannel("arsel_default", "Notifications")
    .build()
```

A campaign can target a different channel — one **your app already created**. If the targeted
channel doesn't exist on the device, the notification posts on the default channel instead: creating
it on the fly would register it under the default channel's name, leaving two identical entries in
the user's Settings.

**Importance is immutable after creation.** Android will not let an app raise a channel's importance
later, by design — so decide it before you ship, and use several channels (`orders`, `promotions`)
rather than one, so a user can silence marketing without silencing receipts.

`channelImportance` in `diagnostics()` reports the resolved value. `0` (`IMPORTANCE_NONE`) means the
user blocked that channel: messages are delivered and never shown.

## Icons and colour

```kotlin
.smallIcon(R.drawable.ic_stat_notify)
.notificationColor(ContextCompat.getColor(this, R.color.brand))
```

The small icon must be a **white-on-transparent** silhouette. Android masks it, so a full-colour icon
renders as a white square — the single most common cosmetic bug in any push integration.

If you don't set one, the SDK falls back to the app icon (a `0` resource id would crash on post).

## Android 13+ runtime permission

`POST_NOTIFICATIONS` is a runtime permission from API 33. **You decide when to prompt** — a prompt
fired at first launch, before the user knows what your app does, is the most reliable way to get a
permanent denial.

```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    // your UI
}

// After your own primer explaining what you'll send:
Arsel.requestNotificationPermission(launcher)
```

The SDK records that the prompt was launched, because the OS reports "never asked" and "asked and
refused" identically — without that flag a refusal keeps being reported as `NOT_DETERMINED` forever.

Reported state is a tri-state, not a boolean:

| `enablementStatus` | Means |
| --- | --- |
| `AUTHORIZED` | granted |
| `DENIED` | prompted and refused, or switched off in settings |
| `NOT_DETERMINED` | never prompted |

**`DENIED` is not an opt-out.** It's an OS-level state the user can reverse in settings, and the
device stays registered so it becomes reachable again the moment they do. Only `optOut()` is an
opt-out.

## Rendering

The backend sends **data-only, priority-high** messages, so the SDK renders every notification
itself. That is what makes delivery, display and suppression measurable — an FCM `notification` block
would be rendered by the OS with the app never waking, and nothing could be reported.

| Feature | |
| --- | --- |
| Title / body | plain text |
| Image | `BigPictureStyle`, bounded fetch on the FCM background thread |
| Action buttons | up to **3** (Android renders no more; extras are dropped at the source, where the truncation is visible) |
| Deep link | per-notification, and optionally per-action |
| Collapse | a redelivery with the same collapse id **replaces** the notification instead of stacking |

Notification ids are derived deterministically from the collapse id (or message id), using
`String.hashCode` — specified by the language, so it is stable across processes, app versions and
devices.

## Taps and deep links

A tap goes to `NotificationTapActivity`, which fires exactly one engagement and routes:

1. the action's deep link, if the tapped action has one
2. otherwise the notification's deep link
3. otherwise your launcher activity

The link is opened with `ACTION_VIEW` **constrained to your package**, so a campaign can never route
a user into an arbitrary third-party app. A link that resolves to nothing falls back to the launcher.

Declare the intent filter you want to receive:

```xml
<activity android:name=".ProductActivity" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="myapp" android:host="product" />
    </intent-filter>
</activity>
```

An action-button tap also cancels the notification — `setAutoCancel` only covers the body tap, so
without it the notification would sit in the shade after the user had already acted on it.

> **Custom key-value pairs from a campaign do not currently reach your Activity on tap.** Non-`arsel_`
> keys are parsed off the payload, but the drop-in tap intent carries only the SDK's own extras. If
> you need campaign metadata in-app today, put it in the deep link's query string, or run your own
> `FirebaseMessagingService` (below) where you have the raw data map.

## Engagement reporting

| Event | When |
| --- | --- |
| `delivered` | the SDK received and claimed the message — regardless of what the OS then does |
| `displayed` | the notification actually posted |
| `suppressed` | the OS refused it, with a reason |
| `opened` | body tap |
| `clicked` | action-button tap, with its `actionId` |
| `dismissed` | swiped away |

**Exactly one engagement per tap** — `opened` *or* `clicked`, never both. Firing both would make the two
counters identical by construction and neither would mean anything.

Suppression reasons:

| Reason | Cause |
| --- | --- |
| `permission_denied` | `POST_NOTIFICATIONS` not granted (API 33+) |
| `app_blocked` | the user switched notifications off for the whole app |
| `channel_blocked` | the target channel is at `IMPORTANCE_NONE` |

A widening gap between `delivered` and `displayed` is the number to watch: it is users who are
technically reachable and see nothing.

Engagements are batched (max 50 per call). Taps flush immediately because they drive automation triggers;
delivery signals coalesce briefly.

## Hosts with their own `FirebaseMessagingService`

Remove ours and forward:

```xml
<service android:name="sa.arsel.push.fcm.ArselPushService" tools:node="remove" />
```

```kotlin
override fun onNewToken(token: String) {
    Arsel.setPushToken(token)
    // your logic
}

override fun onMessageReceived(message: RemoteMessage) {
    if (!Arsel.handlePushData(applicationContext, message.data)) {
        // your push
    }
}

override fun onDeletedMessages() {
    Arsel.handleDeletedMessages()
}
```

`handlePushData` returns `true` if it claimed the message, so it never double-handles yours. Call it
first and fall through on `false`. If you gate on `Arsel.isArselData()` instead, use 1.3.2 or later:
before that it recognised notification envelopes only, so Arsel's in-app sync pushes landed in your
handler and in-app messages never refreshed. In this mode you have the raw data map, so your own
campaign key-value pairs are available to you directly.

Keep the SDK's App Startup `InitializationProvider` merged in (don't remove it with
`tools:node="remove"`). It is how `initialize()` fetches the current FCM token, which matters for
installs whose `onNewToken` already fired before you added Arsel — that callback won't fire again.

## Testing

1. `Arsel.registerNow()` then `Log.d(TAG, Arsel.diagnostics().toString())`
2. Want: `hasPushToken = true`, `isRegistered = true`, `hasDeviceSecret = true`,
   `enablementStatus = AUTHORIZED`
3. Send a test push from the dashboard
4. Watch delivered → displayed → opened move within seconds

Force-stopped apps receive nothing until the user launches them again — that's an OS rule, not a bug.
See [Troubleshooting](troubleshooting.md) for the OEM-specific variants of it.
