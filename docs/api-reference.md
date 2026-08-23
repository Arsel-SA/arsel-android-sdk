# API reference

`sa.arsel.core.Arsel` — a Kotlin `object`, so it's `Arsel.INSTANCE` from Java, and every
method carries `@JvmStatic`.

**Every entry point is crash-isolated.** Failures are logged, never thrown at your app. Calls made
before `initialize()` are logged and ignored; getters return `null`.

---

## `initialize(context, config)`

```kotlin
fun initialize(context: Context, config: ArselConfig)
```

Idempotent. Call once from `Application.onCreate()`. Creates the default notification channel,
requests the current FCM token via the `push-fcm` bridge, and reports device state.

Warns loudly if `firebase-messaging` is absent — push cannot work without it, but events and identity
still will.

### `ArselConfig.Builder(clientKey, baseUrl)`

| | Default | |
| --- | --- | --- |
| `clientKey` | — | The org's publishable `pub_…` key. Safe in an APK. Blank throws |
| `baseUrl` | — | **HTTPS enforced**; anything else throws. `http://localhost`, `http://127.0.0.1` and the emulator's `http://10.0.2.2` are the exceptions, for a local backend. Trailing slashes trimmed |
| `.defaultChannel(id, name)` | `arsel_default` / `Notifications` | Created at `initialize()` |
| `.smallIcon(resId)` | app icon | White-on-transparent silhouette |
| `.notificationColor(color)` | none | Accent colour |
| `.logLevel(level)` | `WARN` | `VERBOSE`/`DEBUG`/`INFO`/`WARN`/`ERROR`/`NONE` |
| `.networkTimeoutMs(ms)` | `15_000` | Per request |

`build()` throws `IllegalArgumentException` for a blank `clientKey` or a non-HTTPS `baseUrl` — the
two things that would otherwise fail silently on every call.

Developing against a backend on your own machine? Use `http://10.0.2.2:8076` from an emulator —
`localhost` inside the emulator is the emulator itself, not your host.

---

## `track(name, properties = emptyMap())`

```kotlin
@JvmOverloads fun track(name: String, properties: Map<String, Any?> = emptyMap())
```

Records something the user did. Persisted before send; returns immediately; safe from any thread.

Blank names and names starting `arsel.` are ignored. Names are truncated at 80 characters.
Strings, numbers and booleans are sent as-is; everything else is `toString()`ed; nulls are omitted.

Needs no push token, no permission, no Firebase. See [Events](events.md).

---

## `identify(externalId = null, email = null, phoneNumber = null)`

```kotlin
@JvmOverloads fun identify(externalId: String? = null, email: String? = null, phoneNumber: String? = null)
```

Client-asserted identity. Everything tracked beforehand under the anonymous identity merges onto the
contact this resolves to. Identifiers are persisted and ride every later event — call once per login.

At least one argument must be non-blank; a call with none is logged and ignored. `email` and
`phoneNumber` are shape-checked client-side (basic email shape; E.164 for phone, e.g.
`+9665xxxxxxxx`) — an invalid value is rejected with a logged error rather than stored, because a
stored bad identifier would turn every subsequent event into a permanent 400. Emits
`arsel.identify` immediately.

Changing `externalId` to a different value drops any stored email and phone — they belonged to the
previous identity. Re-assert them if they carry over.

Prefer `externalId` alone. See [Identity](identity.md).

---

## `reset()`

```kotlin
fun reset()
```

Logout. Clears the contact binding, **rotates the anonymous id**, and leaves the installation
registered and receiving.

**Not an unsubscribe** — see [`optOut()`](#optout).

---

## `optOut()`

```kotlin
fun optOut()
```

Durable, user-initiated opt-out. The backend will **not** resurrect this installation on a later
registration; re-opting-in is a separate, explicit act.

Call it only when the user actually asks to stop receiving notifications. Calling it on logout leaves
the handset permanently unreachable.

---

## `requestNotificationPermission(launcher)`

```kotlin
fun requestNotificationPermission(launcher: ActivityResultLauncher<String>)
```

Android 13+ runtime permission, through a launcher you registered. No-op below API 33.

Records that the prompt was launched — the OS reports "never asked" and "asked and refused"
identically. Marked on launch rather than on the result, because you own the result callback and may
never tell us.

Call it after your own primer. See [Push notifications](push-notifications.md#android-13-runtime-permission).

---

## `getInstallationId()` / `getAnonymousId()`

```kotlin
fun getInstallationId(): String?    // names the HANDSET; survives logout
fun getAnonymousId(): String?       // names the PERSON; rotated by reset()
```

Both `null` only before `initialize()`. They are not interchangeable — see
[Identity](identity.md#three-ids-and-they-are-not-interchangeable).

`getInstallationId()` is what you send to your own backend for the server-to-server contact binding
path.

---

## `diagnostics()`

```kotlin
fun diagnostics(): ArselDiagnostics?
```

A snapshot safe to paste into a support ticket: **no FCM token and no device secret**.
`toString()` is multi-line and copy-paste shaped.

| Field | Means |
| --- | --- |
| `sdkVersion` | as sent in `X-Arsel-SDK` |
| `installationId` | the backend's natural key for this device |
| `anonymousId` | the person-shaped identity |
| `hasAssertedIdentity` | `identify()` supplied at least one identifier |
| `hasDeviceSecret` | `false` means no authenticated call can succeed |
| `hasPushToken` | `false` means nothing can be delivered |
| `isRegistered` | the backend confirmed the current device facts |
| `subscriptionId`, `subscriptionStatus` | e.g. `ACTIVE` / `REVOKED` |
| `lastResponseCode` | `-1` = no response at all; `0` = no call yet |
| `lastResponsePath` | which call it was |
| `queueDepth` | everything waiting to be delivered |
| `eventQueueDepth` | the events half — the two fail independently |
| `lastFlushAtMs` | last drain attempt of any kind |
| `enablementStatus` | `AUTHORIZED` / `DENIED` / `NOT_DETERMINED` |
| `defaultChannelId`, `channelImportance` | `0` = user-blocked channel: delivered, never shown |
| `isFirebasePresent` | `false` means the host forgot the dependency |

---

## `registerNow()` / `flushNow()`

```kotlin
fun registerNow()   // re-register unconditionally, whatever the device fingerprint says
fun flushNow()      // send anything queued now, instead of on the batcher's window
```

Neither is needed in normal operation — registration is driven by `initialize()`, token rotation and
app foreground. They exist for QA harnesses and for an integrator proving their setup works. Delivery
still requires network.

---

## Push plumbing

Called for you by `push-fcm`. Public for hosts running their own `FirebaseMessagingService`.

```kotlin
fun setPushToken(token: String)
fun isArselData(data: Map<String, String>): Boolean
fun handlePushData(context: Context, data: Map<String, String>): Boolean   // true = claimed
fun handleDeletedMessages()
fun handleNotificationOpen(intent: Intent)      // OPENED (body) or CLICKED (action button)
fun handleNotificationDismiss(intent: Intent)   // DISMISSED
fun isInitialized(): Boolean
```

`handlePushData` claims a message in two phases — pending before the render, done after — so a render
that throws releases the claim and FCM's redelivery gets another chance. Marking a message seen up
front would suppress the notification *and* its engagements permanently on one transient failure.

The last two are wired to the SDK's own tap Activity and dismiss receiver; call them only if you
route notifications yourself.

---

## Public types

| Type | |
| --- | --- |
| `ArselConfig` / `.Builder` | configuration |
| `ArselDiagnostics` | the snapshot above. Not a `data class` — the field set grows, and `copy()`/`componentN()` are binary API |
| `ArselPushMessage` | a parsed inbound push |
| `ArselPushAction` | `actionId`, `label`, `deepLink` |
| `EngagementEvent` | `DELIVERED`, `DISPLAYED`, `SUPPRESSED`, `OPENED`, `CLICKED`, `DISMISSED` |
| `SuppressionReason` | `PERMISSION_DENIED`, `CHANNEL_BLOCKED`, `APP_BLOCKED` |
| `PushEnablementStatus` | `AUTHORIZED`, `DENIED`, `NOT_DETERMINED` (+ iOS-only members) |
| `PushPlatform` | `ANDROID`, `IOS`, `WEB` |
| `LogLevel` | `VERBOSE` … `NONE` |

The module is compiled in Kotlin **explicit API mode**: everything public is deliberate, and anything
not listed here is internal and may change without a major version.

---

## Cross-SDK parity

The Android and web SDKs deliberately share their conceptual surface; the two places the names
diverge are per-platform on purpose (push opt-in goes through each platform's own permission
machinery, and initialization follows each platform's idiom).

| Concept | Android | Web |
| --- | --- | --- |
| Initialize | `initialize(context, config)` | `init(config)` |
| Identity | `identify(externalId, email, phoneNumber)` | `identify(...)` |
| Custom events | `track(name, properties)` | `track(name, properties)` |
| Logout | `reset()` | `reset()` |
| Durable opt-out | `optOut()` | `optOut()` |
| Push opt-in | `requestNotificationPermission(launcher)` | `promptForPush()` — deliberate per-platform names |
| Force delivery | `flushNow()` | `flushNow()` |
| Person-shaped id | `getAnonymousId()` | `getAnonymousId()` |
| Support snapshot | `diagnostics()` | `diagnostics()` |

---


### Invalid configuration

All three SDKs apply the same four rules, in the same order, and all three respond the same way:
they **log an error, decline to start, and never throw**.

1. `clientKey` is non-blank
2. `clientKey` begins `pub_` — the check that catches a secret API key shipped inside an app bundle
3. `baseUrl` is HTTPS, except plain http to `localhost` / `127.0.0.1` (and `10.0.2.2` on Android,
   the only address an emulator can reach the developer's host on)
4. `baseUrl` parses as a URL

Nothing is collected while a config error stands, and no call has any effect. The reason is
readable at any time from the support snapshot:

| SDK | Reading it |
| --- | --- |
| Android | `Arsel.diagnostics()?.configError` |
| Web | `(await Arsel.diagnostics()).configError` |
| iOS | `Arsel.diagnostics()?.configError` |

Refusing rather than throwing is deliberate. The mistake is made at development time but the
failure lands at runtime on a user's device — the key may come from a build variant, a remote
config, or a CI secret that arrived empty — and an analytics SDK crashing an app over its own
configuration is a worse outcome than losing telemetry. `diagnostics()` answers with the reason
even before initialization, which is the state it describes.

## Network calls

For your security review. The SDK talks only to the `baseUrl` you configure.

| Call | Auth |
| --- | --- |
| `POST /v1/events/send` | `Authorization: Bearer <clientKey>` + `Idempotency-Key` |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions` | none on create; mints the device secret |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions/state` | `X-Arsel-Device-Auth` |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions/unsubscribe` | `X-Arsel-Device-Auth` |
| `POST /api/v1/orgs/{clientKey}/push/engagements` | `X-Arsel-Device-Auth` |

The full contract — endpoints, headers, registration fields and the inbound `arsel_*` payload keys —
is in the [wire reference](wire-reference.md); changes to it are recorded in the
[changelog's Wire sections](../CHANGELOG.md).
