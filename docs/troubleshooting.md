# Troubleshooting

Start here, always:

```kotlin
Log.d("MyApp", Arsel.diagnostics().toString())
```

It contains no FCM token, no device secret and no contact token, so it is safe to paste into a
support ticket.

```
Arsel diagnostics
  sdkVersion            = 1.0.0
  installationId        = 0192f3…
  anonymousId           = 9f2c…
  hasAssertedIdentity   = true
  hasDeviceSecret       = true
  hasPushToken          = true
  isRegistered          = true
  …
```

---

## Setup

### `SDK not initialized — call Arsel.initialize() first`

Every method logs this and no-ops before `initialize()`. Call it from `Application.onCreate()`, not
from an Activity — a push can arrive while no Activity exists.

### `firebase-messaging not on the classpath`

Push cannot work; events and identity still will. Add the Firebase BoM and
`com.google.firebase:firebase-messaging` to your app. The SDK declares Firebase `compileOnly` on
purpose so your app owns the version.

### `ArselConfig: baseUrl must be HTTPS` / `clientKey must not be blank`

`build()` throws for both, deliberately — they're the two mistakes that otherwise fail silently on
every subsequent call. Check that your `BuildConfig` field actually reached the release build; a
`null` from a missing Gradle property becomes a blank key.

### The client key looks wrong

The client key starts `pub_`. If yours starts `be_` you have a **secret API key** — it will be
rejected, and it must never be in an APK.

---

## Events

### `eventQueueDepth` only grows

Delivery is failing and events are being kept, which is intended. Read `lastResponseCode`:

| Code | Cause | Fix |
| --- | --- | --- |
| `401` | Bad or wrong-class key | Use the `pub_…` client key |
| `403` | Origin rejected | Native apps send no `Origin`; a `403` here means a proxy is adding one |
| `404` | The org's push channel isn't enabled yet | Retried automatically — nothing to do |
| `429` | Rate limited | Kept and retried on a jittered backoff (5s, doubling, capped at 5 min), never before the `Retry-After` the server sent. Sustained `429`s mean the organization is over its event budget, so send fewer events rather than waiting it out |
| `-1` | No response at all | Offline, DNS, TLS, or a corporate proxy |
| `0` | Nothing has been sent yet | `initialize()` may not have run |

Once the cause clears, the whole backlog drains in order.

### Nothing appears in the dashboard, but `lastResponseCode = 202`

It was accepted. Look under the **contact**, not the event list — a client-key event auto-creates its
definition, so a brand-new name shows up only after the first one lands.

### A `track()` call did nothing

Blank names and names starting `arsel.` are ignored. Set `.logLevel(LogLevel.DEBUG)` and the SDK says
which.

### Events stop while the app is backgrounded

Expected. WorkManager owns the schedule and batches work to save battery. `flushNow()` forces a drain
for testing.

### Events vanish on some devices but not others

Aggressive OEM battery managers (Xiaomi, Huawei, Oppo, Vivo, Samsung) suspend WorkManager for apps
the user hasn't opened recently. Events aren't lost — they're persisted and drain on next launch.
[dontkillmyapp.com](https://dontkillmyapp.com) documents the per-OEM settings.

---

## Push

### No notifications at all

Work down this list:

| Check | |
| --- | --- |
| `hasPushToken = false` | Firebase never handed one over. Check `google-services.json` matches your package name |
| `isRegistered = false` | Registration never confirmed. Check `lastResponseCode` |
| `hasDeviceSecret = false` | The one-time secret was never captured. No authenticated call can succeed; force `registerNow()` |
| `enablementStatus = DENIED` | Permission not granted, or notifications switched off for the app |
| `channelImportance = 0` | The user blocked that channel. Delivered, never shown — **and you cannot raise it programmatically** |
| App was force-stopped | An OS rule: nothing is delivered until the user launches the app again |
| Emulator | Needs a **Google Play** system image; a plain AOSP image has no FCM |

### Delivered but not displayed

That's a suppression, and the reason is reported:

| Reason | Fix |
| --- | --- |
| `permission_denied` | Prompt for `POST_NOTIFICATIONS` (API 33+) |
| `app_blocked` | The user switched the app's notifications off in system settings |
| `channel_blocked` | The channel is at `IMPORTANCE_NONE` — the user must re-enable it |

A widening delivered/displayed gap is the number to watch: those users are reachable and see nothing.

### The icon is a white square

The small icon must be a **white-on-transparent silhouette**. Android masks it, so a full-colour
asset renders as a solid white block. This is Android's behaviour, not the SDK's.

### Notifications stack instead of replacing

Notification ids are derived from the campaign's collapse id. Without one, each message is distinct
and stacks — which is usually what you want. Set a collapse id on the campaign to replace instead.

### Taps don't open the right screen

The deep link is opened with `ACTION_VIEW` **constrained to your package**. If nothing in your
manifest resolves it, the SDK falls back to your launcher. Verify:

```bash
adb shell am start -a android.intent.action.VIEW -d "myapp://product/123" <your.package>
```

If that doesn't open the screen either, the intent filter is the problem, not the SDK.

### Opens and clicks are equal

They shouldn't be — `opened` is a body tap and `clicked` is an action button, exactly one per tap. If
they track each other exactly, something in your stack is double-reporting. Check whether you're
calling `handleNotificationOpen()` yourself *and* letting the SDK's tap Activity handle it.

### Only some notifications arrive

FCM drops a device's backlog when too many messages queue up or the device is offline past the
retention window; it then fires `onDeletedMessages`. The SDK re-reports device state so you can tell
"the handset is alive and the OEM killed us" apart from a delivery-side fault. Nothing can recover
the dropped messages — that's FCM's design.

### It worked, then stopped after a reinstall

A reinstall mints a new FCM token and a new installation id. Registration should re-run at
`initialize()`.

If the user had `optOut()`'d, they normally stay unsubscribed — that's intended, not a bug. The
device-level tombstone is keyed to the *old* installation id and no longer matches, but revoking
their last active device also marked the **contact** unsubscribed from push, and a new registration
does not reverse that.

The exception is a contact who still has another active device: the contact stays subscribed, so the
reinstalled handset becomes reachable again. Call `optOut()` again if it should stay off.

---

## Build

### Duplicate class / manifest merger conflicts

Your app and the SDK both declare a `FirebaseMessagingService`. Remove ours and forward — see
[Push notifications](push-notifications.md#hosts-with-their-own-firebasemessagingservice).

### Manifest merger fails on `android:fullBackupContent` / `android:dataExtractionRules`

Your app declares its own backup rules and so does the SDK, so the merger refuses to pick one. The
conflict is deliberate: the SDK excludes its stored `installationId` and one-time `deviceSecret`
from backup, and silently dropping that exclusion is worse than a build failure. Auto Backup or a
device transfer would otherwise clone both onto a second handset, and because the server verifies
the secret by hash with no device binding, the two handsets would then authenticate as the same
subscription.

Keep your own rules and carry the exclusion across. Both transports must be named separately —
`<cloud-backup>` is Google's backup, `<device-transfer>` is the direct handset-to-handset copy, and
device transfer is the one that clones a secret onto a phone the user still holds the original of:

```xml
<!-- your res/xml/data_extraction_rules.xml (Android 12+) -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="arsel_push.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="arsel_push.xml" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- your res/xml/backup_rules.xml (Android 11 and below) -->
<full-backup-content>
    <exclude domain="sharedpref" path="arsel_push.xml" />
</full-backup-content>
```

Then tell the merger yours wins:

```xml
<application
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    tools:replace="android:fullBackupContent,android:dataExtractionRules">
```

### `Task :app:processDebugGoogleServices` fails

`google-services.json` is missing, or its package name doesn't match your `applicationId` — including
suffixes like `.debug`. Register every variant's id in Firebase.

### R8 / ProGuard

No consumer rules are needed; the SDK ships its own. If you see reflection failures on a minified
release build, that's worth a bug report rather than a keep rule.

---

## Getting help

Include:

1. `Arsel.diagnostics().toString()`
2. Device model, Android version, OEM skin
3. Whether it's a release or debug build, and whether minification is on
4. The failing call, and logcat filtered on `Arsel`
