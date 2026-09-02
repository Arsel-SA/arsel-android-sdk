# Changelog

All notable changes to the Arsel Android SDK.

This project follows [Semantic Versioning](https://semver.org/). A breaking change costs every
integrator an app-store release, so the public API and the wire contract are frozen within a major
version: additive changes ship as minor releases, and anything breaking waits for `2.0.0`.

---

## [1.2.0] — 2026-09-02

### Added

- **`arsel.app_installed`.** Emitted once, on the first launch after the app is installed, ahead of
  that launch's `arsel.session_start`. Carries `app_version`, `sdk_version` and `platform`. Its flag
  lives beside the rest of the SDK's state, which the OS deletes with the app, so a reinstall counts
  again while `reset()` and `optOut()` do not.

  **Devices that already have your app get no install event when they update to this version.** They
  are seeded silently on their first launch: emitting would have reported the entire installed base
  as installs on the day you shipped. Install-based segments start empty and fill forward.

---

## [1.1.0] — 2026-08-23

### In-app messaging

Messages authored in Arsel now render inside the host app, with no notification permission and no
prompt of any kind.

The division of labour is the design: the server resolves audience, consent, campaign window,
grants and lifetime caps into a per-device catalogue, and the SDK evaluates only the trigger and
its session-scoped caps locally — so drawing a message costs no network round-trip and works with
no connection at all.

- Four trigger types resolve from events the SDK already emits: app open (from
  `arsel.session_start`, not the raw foreground callback, which is rate-limited and would fire for
  a two-second tab-out), screen views, and custom events.
- `Arsel.screen(name, properties)` — records a screen view. One event, two consumers: it reaches
  segments and automations exactly as `track()` does, and it is the trigger source for
  screen-scoped messages. Deliberately not a `track()` convention name, because the backend treats
  a screen view and a custom event of the same name as different trigger types.
- `Arsel.setInAppMessagingEnabled(enabled)` — holds messages back for the moments a host knows are
  wrong, such as a checkout step. Not persisted: it describes the current screen, not the device.
- Five layouts — modal, top and bottom banners, fullscreen, image-only — drawn in code rather than
  from XML, so the library ships no layout resources to collide with a host app's own.
- Impressions, clicks and dismissals are reported through the existing durable queue, so they
  survive an offline spell and process death. An impression is recorded only once the view is
  actually attached: a message abandoned during its delay window leaves no trace at all.
- A reserved `arsel_iam_sync` push refreshes the catalogue and renders nothing. It is handled
  before the message parse, the claim and the delivered engagement, so a sync ping can never book
  delivery against a campaign that was never sent.

### Changed

- `ApiClient` gains a conditional `get()`, and `classify()` now treats `304 Not Modified` as a
  success. Without that branch a conditional request falls through to a permanent failure and the
  cache is discarded on every successful revalidation — the opposite of what the request asks for.
- `ForegroundWatcher` tracks the resumed Activity behind a `WeakReference`, so a message is drawn
  into the screen actually in front of the user rather than one sitting behind a dialog.
- **`baseUrl` accepts plain http for loopback** — `localhost`, `127.0.0.1` and `10.0.2.2`, the last
  being the only address an emulator can reach the developer's host on. Without it "run the backend
  locally" is impossible on Android, which is exactly where every integration starts. The match is
  anchored, so `http://localhost.evil.com` cannot slip through on a prefix.
- **`Builder.build()` no longer throws.** It ran from `Application.onCreate`, so an invalid key took
  the host app down at launch — contradicting this SDK's own "never crashes the host" invariant,
  which `initialize()` could not enforce because the throw happened before it was called.
  `initialize()` now logs, declines to start, and keeps the reason readable via
  `diagnostics().configError`, which answers even before initialization since that is the state it
  describes. The four rules now match the web and iOS SDKs exactly, including the `pub_` prefix
  check that catches a secret API key compiled into an APK.

### Fixed

- **A notification with no resolvable icon rendered as a blank square.** `applicationInfo.icon` is
  itself `0` when the host declares no `android:icon`, so the "use the app icon" fallback resolved
  to resource 0. It falls back to a system icon now and logs at error level naming the two ways to
  fix it properly — a silent blank icon is the kind of thing an integrator ships without noticing.
- **The FCM token is re-requested when none was captured.** `requestCurrentToken()` fired exactly
  once, from `initialize()`, with no retry and no way for the host to re-trigger it; a cold start
  with no network answers `SERVICE_NOT_AVAILABLE`, and a handset whose GMS check-in has not landed
  answers `AUTHENTICATION_FAILED`. `onNewToken` masks this on a fresh install because a token gets
  created, but it fires only on creation or rotation — so an app that already uses Firebase and
  adopts this SDK could be left unreachable by one unlucky cold start until the token happened to
  rotate. The retry hangs off `syncDeviceState()`, which already runs on every foreground.
- **An http loopback `baseUrl` retried forever with no event ever sent.** `ApiClient` cast the
  connection to `HttpsURLConnection`, so a non-TLS URL threw `ClassCastException`, which the method
  swallowed as a retryable network error.

## [1.0.0] — 2026-08-17

First public release. Two subsystems in one artifact, deliberately independent of each other.

### Events and identity — `sa.arsel:core`

Works on a handset that has never granted notification permission, has no FCM token, and does not
have Firebase on the classpath at all.

- `Arsel.track(name, properties)` — durable custom events, queued to disk and drained by
  WorkManager, so they survive process death, offline periods and app kills.
- `Arsel.identify(externalId, email, phoneNumber)` — everything tracked beforehand under the
  anonymous identity merges onto the contact it resolves to. `email` and `phoneNumber` are
  shape-checked client-side; a changed `externalId` drops the previous identity's stored email and
  phone rather than carrying them onto a different person.
- An anonymous identity minted at first run and persisted, exposed by `Arsel.getAnonymousId()`.
  Distinct from the installation id, which names the handset — `reset()` rotates the anonymous id so
  a shared device never hands the next user the previous one's history.
- Automatic `arsel.session_start` / `arsel.session_end`, derived from foreground/background
  transitions with no timers. A session ends after 30 minutes away, and the end event is emitted on
  the next foreground backdated to when the app actually left. A user who never returns produces no
  `session_end` — a fabricated end time is worse than a missing one.
- The `arsel.` event-name prefix and the `arsel_*` push data keys are reserved and refused to hosts,
  so a customer's own names can never collide with the SDK's.

### Push notifications — `sa.arsel:push-fcm`

Firebase glue only. `core` never touches a Firebase type, which is what lets the events API run with
no Firebase present.

- Drop-in delivery: `ArselPushService` claims Arsel messages, renders the notification on a
  configured channel, and reports engagement. Hosts running their own `FirebaseMessagingService`
  call `Arsel.isArselData(data)` / `Arsel.handlePushData(...)` instead.
- Engagement reporting distinguishes `DELIVERED`, `DISPLAYED`, `SUPPRESSED` (with a reason),
  `OPENED`, `CLICKED` (action buttons only) and `DISMISSED`.
- Notification actions, deep links, images and per-campaign channel overrides.
- `Arsel.optOut()` — a durable, user-initiated opt-out, distinct from `reset()`, which is logout and
  leaves the device subscribed.
- Registration carries the anonymous id, so a push subscription lands on the same contact the events
  attach to. Backends that already know the signed-in user can bind authoritatively instead, via
  `POST /v1/push/devices` with the installation id.

### Reliability

- **Persist before send; confirm on 2xx.** Requests are queued to disk, removed by id, and the drain
  stops at the first retryable failure to preserve oldest-first ordering.
- Event sends carry an `Idempotency-Key` — the queued request's persisted id, identical across
  retries — closing the duplicate window between a server 2xx and a dequeue that never happened.
- The device secret is issued exactly once on first registration and presented as
  `X-Arsel-Device-Auth` on every subsequent mutation.
- Inbound messages are claimed in two phases, so a render failure releases the claim and FCM's
  redelivery gets another chance rather than suppressing the notification permanently.
- `Retry-After` is honoured on `429`.

### Operability

- `Arsel.diagnostics()` — a snapshot safe to paste into a support ticket: no FCM token, no device
  secret. Registration and subscription state, last response code and path, queue depths, permission
  state, channel importance, and whether Firebase is present.
- No callbacks into the host by design — a bug in a host callback invoked mid-teardown takes down a
  customer's app. Observation is by polling `diagnostics()`.
- Auto Backup and device-transfer exclusion rules for the SDK's preferences, so a restored backup
  never resurrects another device's installation identity.

### Documentation

A `docs/` set covering [events](docs/events.md), [identity](docs/identity.md),
[push notifications](docs/push-notifications.md), the [API reference](docs/api-reference.md),
[troubleshooting](docs/troubleshooting.md), [Play Data safety](docs/data-safety.md),
[migrating from CleverTap](docs/migrating-from-clevertap.md) and the
[wire reference](docs/wire-reference.md).
