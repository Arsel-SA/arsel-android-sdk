# Arsel — Sample / Test App

A standalone Android app (its **own repo**) that consumes the
**[Arsel Android SDK](https://github.com/Arsel-SA/arsel-android-sdk)** exactly the way a
real integrator would. This is the test sandbox — no production app required.

> Contains no SDK source. It is its own Gradle build and depends on the published artifact from
> `mavenLocal()`, like a customer would.

---

> 📖 **Step-by-step setup and what each diagnostics field means: [`HARNESS.md`](HARNESS.md).**

## What you need

- **Android Studio** + the bundled **JDK 17+**.
- An emulator image **that includes Google Play** — FCM needs Google Play services. Android push works
  fine on an emulator; no physical phone required. (iOS is a different story and needs real hardware.)
- A **Firebase project you control**. In production every customer brings their own.
- For the full round trip: an **Arsel org with push enabled** — create a push app in your Arsel
  dashboard, or contact Arsel to enable push for your org.

> **The Firebase project must be the same one whose service-account JSON is uploaded to Arsel.** FCM
> registration tokens are project-bound: a token minted under project A cannot be sent to using
> project B's credentials — the send fails with `SENDER_ID_MISMATCH`.

---

## Step 1 — Publish the SDK locally (once, and after every SDK change)

In the SDK repo:

```bash
cd ../arsel-android-sdk
export JAVA_HOME=/usr/lib/jvm/<jdk>          # the DIRECTORY, not .../bin/java
./gradlew publishToMavenLocal
```

Produces `sa.arsel:core` and `sa.arsel:push-fcm` in `~/.m2` (the SDK's
`VERSION_NAME`, which `gradle/libs.versions.toml` here must match).

## Step 2 — Configure this app

The app has two build flavors, `staging` and `prod`, each pointing at its own backend with its own
client key. They install side by side — "Arsel Sample (staging)" and "Arsel Sample (prod)" — because each
gets its own `applicationId`, and therefore its own SDK state. Use **`staging`** for day-to-day
testing; `prod` does not build until you provision it.

1. **Firebase config** — Firebase console → your project → add an **Android app** whose package name
   is the flavor's applicationId, **suffix included**:
   - `staging` → `com.example.arselsample.staging` → `app/src/staging/google-services.json`
   - `prod` → `com.example.arselsample` → `app/src/prod/google-services.json`

   Use two separate Firebase projects; a service account authorizes sending to its whole project, so
   sharing one would let staging push to prod-registered devices. Both files are git-ignored — never
   commit them, and never put one at `app/`, where the plugin would apply it to both flavors.
2. **Arsel config** — in `app/build.gradle.kts`, set `ARSEL_BASE_URL` on the `staging` flavor to the
   test environment Arsel gives you, and `ARSEL_CLIENT_KEY` on the flavor you are using to that
   org's opaque **`pub_…` publishable key** (shown when push is enabled for the org). Use a dedicated
   test org — never a real customer's. The base URL **must be HTTPS**; the SDK rejects cleartext at
   config build time.

## Step 3 — Run

Pick a **Google Play** emulator → select the **stagingDebug** variant → Run, or from the terminal:

```bash
./gradlew :app:installStagingDebug
```

On launch the app initializes the SDK, captures the FCM token, and registers with Arsel. The screen
shows the **backend it is talking to**, the **installation id**, the **FCM token**, live
**diagnostics**, and an event log.

Watch Logcat with the tag `Arsel`.

---

## Step 4 — Send a push

### Option A — the real path (recommended)

This is the only option that exercises the whole system: signature verification, engagement
reconciliation, analytics rollups and attribution. Send a push campaign from your **Arsel
dashboard** to the bound contact, or from your backend via the API:

```
POST {baseUrl}/v1/push/send        (API-key authenticated)
{
  "contact_ids": ["<contact uuid>"],
  "title": "Hello",
  "body": "From Arsel",
  "message_type": "transactional"
}
```

The device must be **contact-bound** first — see *Binding a contact* below, or the send has no
subscription to target.

### Option B — raw FCM (rendering only)

Useful to isolate "does the SDK render" from "does the backend send". Note the **`arsel_` namespaced
keys** — the SDK claims a message on `arsel_v` and ignores anything else:

```bash
curl -X POST \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  "https://fcm.googleapis.com/v1/projects/YOUR_FIREBASE_PROJECT_ID/messages:send" \
  -d '{
    "message": {
      "token": "PASTE_DEVICE_FCM_TOKEN",
      "android": { "priority": "high" },
      "data": {
        "arsel_v": "1",
        "arsel_mid": "test-0001",
        "arsel_title": "Hello from Arsel",
        "arsel_body": "Data-only test push",
        "arsel_deep_link": "https://example.com/welcome"
      }
    }
  }'
```

Data-only + `priority: high` is exactly what the backend sends, so this renders in the background too.

> **Firebase Console "Send test message" is not useful here.** It always attaches a `notification`
> block, which the OS renders itself — bypassing the SDK entirely, so no engagement is reported. Use curl.

**Keys the SDK reads:** `arsel_v`, `arsel_mid`, `arsel_sig`, `arsel_kid`, `arsel_title`, `arsel_body`,
`arsel_image`, `arsel_deep_link`, `arsel_channel_id`, `arsel_actions`, `arsel_collapse_id`. Anything
not prefixed `arsel_` is preserved as `hostData` and handed back to the app.

Engagements sent via Option B will be rejected server-side as unsigned (no `arsel_sig`) — expected, and
exactly why Option A is the real test.

---

## Binding a contact

A push targets a *contact*, so the device must be linked to one. Two ways:

**Server-to-server (the primary path).** Copy the **installation id** from the app, then from your
backend:
```
POST {baseUrl}/v1/push/devices    (API-key authenticated)
{ "installation_id": "<from the app>", "platform": "android", "contact_id": "<contact uuid>" }
```

**Client-asserted (no backend needed).** Type an external ID into the app's *Identity* section and
press **identify(externalId = …)**. Everything tracked beforehand under the anonymous identity merges
onto that contact. This is the app making a claim, which is right for an id your own app already
holds — see the trust-boundary table in [`HARNESS.md`](HARNESS.md).

---

## What to look for

| Signal | Where |
|---|---|
| Event queued | Diagnostics: `eventQueueDepth` rises, then returns to 0 when it drains |
| Event landed | Arsel dashboard: the event appears on the (anonymous) contact's activity |
| Anonymous history merged | After the merge proof, both events sit on ONE contact and the anonymous one is gone |
| Logout rotated the identity | The **Anonymous ID** on screen changes after `reset()` |
| Registration succeeded | Diagnostics: `isRegistered=true`, `hasDeviceSecret=true`, `lastResponseCode=200` |
| Contact bound (client-asserted) | Diagnostics: `hasAssertedIdentity=true` |
| Engagement posted | Diagnostics: `lastResponsePath=…/push/engagements`, `lastResponseCode=202`, queue depth returns to 0 |
| Backend received it | Push analytics in the dashboard: `delivered` → `displayed` → `opened` |

The SDK has **no event callback by design** — it must never call into a host that may be mid-teardown
— so the harness observes it the same way you would in the field: by polling `Arsel.diagnostics()`.

### Event semantics (so the numbers make sense)

- **delivered** — the SDK received the payload.
- **displayed** — `notify()` actually posted it. Different from delivered: if notifications are
  disabled, the OS silently drops it and you get **suppressed** with a reason instead.
- **opened** — the notification body was tapped. **clicked** — an action button was tapped.
  These are *not* the same event; only one fires per tap.

---

## Troubleshooting

- **No FCM token / `FirebaseApp not initialized`** → `google-services.json` missing or wrong package name.
- **Nothing renders** → check the data keys are `arsel_*` (see above); on Android 13+ press **Request
  notification permission** first; confirm you sent to the token currently on screen.
- **Renders in foreground but not background** → you sent a `notification` block (the Console default).
  Use the data-only curl.
- **Register returns 404** → push is not enabled for that org, or `ARSEL_CLIENT_KEY` is not the
  `pub_…` key. The SDK treats this as retryable and keeps trying, so fix the key and press
  **registerNow()**.
- **Register returns 400** → usually a stale SDK build; re-run `publishToMavenLocal`.
- **Engagements stay queued** → `hasDeviceSecret=false`. The secret is issued **once**, on first
  registration. Clear app data and register again to mint a new installation.
- **Sends fail with `SENDER_ID_MISMATCH`** → the app's Firebase project differs from the service
  account uploaded to Arsel.
- **`Could not resolve sa.arsel:push-fcm`** → run `publishToMavenLocal` in the SDK repo.
- **Cleartext/HTTPS error at startup** → `ARSEL_BASE_URL` must be https; use ngrok for a local backend.

## CI

`.github/workflows/ci.yml` checks out the SDK repo as a sibling, publishes it to mavenLocal, and
builds `stagingDebug` with the fake `ci/google-services.ci.json`. The sibling checkout carries no
cross-repo token, so the workflow goes green once both repos are public.

## License

MIT — see [LICENSE](../LICENSE).
