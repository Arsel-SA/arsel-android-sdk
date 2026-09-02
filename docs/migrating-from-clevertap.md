# Migrating from CleverTap (Android)

This covers the **app-side SDK swap**. Bringing existing profiles and device tokens across is a
separate, server-side bulk import — talk to your Arsel contact to run it. Do that **first**, then
ship the app build described here.

The model is *import, then cut over*. There is no period where both SDKs run side by side.

---

## The decision that determines whether this works

**Keep your existing Firebase project.** FCM tokens are bound to your sender id, which CleverTap
never owned — so they survive the move, and imported devices are reachable on day one.

Create a *new* Firebase project and **every Android token is dead**. No import can recover them, and
reach rebuilds only as users open the new build. Make this decision before anything else.

---

## API mapping

| CleverTap | Arsel |
| --- | --- |
| `CleverTapAPI.getDefaultInstance(context)` | `Arsel.initialize(context, config)` |
| `clevertap.pushEvent("Product viewed", map)` | `Arsel.track("product.viewed", map)` |
| `clevertap.onUserLogin(profile)` | `Arsel.identify(externalId = …)` |
| `clevertap.pushProfile(map)` | `identify(...)` for identifiers; contact properties are server-side |
| `clevertap.pushFcmRegistrationId(token, true)` | `Arsel.setPushToken(token)` — automatic unless you run your own service |
| `clevertap.getCleverTapID()` | `Arsel.getInstallationId()` — **not** `getAnonymousId()` |
| `CleverTapAPI.createNotification(context, bundle)` | `Arsel.handlePushData(context, data)` |
| `CleverTapAPI.setDebugLevel(...)` | `.logLevel(LogLevel.DEBUG)` on the config builder |
| `clevertap.pushNotificationClickedEvent(extras)` | automatic — the SDK's tap Activity |
| CleverTap dashboard opt-out | `Arsel.optOut()` |

### Not one-to-one

**`getCleverTapID()` maps to `getInstallationId()`, not `getAnonymousId()`.** CleverTap's ID
identifies the *device*; our anonymous id identifies the *person* and is rotated by `reset()`. If you
were using the CleverTap ID as a user key, stop — see [Identity](identity.md).

**There is no `Charged` event.** CleverTap special-cases purchases with an `Items` array. Arsel has
no reserved commerce event: send your own (`order.placed`) and mark it as a conversion in the
dashboard, mapping the revenue property. Line items are not a first-class structure.

**No `pushProfile` from the app.** Our client API asserts *identifiers* only; contact properties are
written server-side or by import. Page and app script are the least trustworthy places to write a
durable customer record from.

**No App Inbox, no in-app messages, no native display units.** If you use those, they don't have an
equivalent yet and need to come out of the app.

---

## Identity mapping

| CleverTap | Arsel | |
| --- | --- | --- |
| `Identity` | `externalId` | **The join key.** Same value in the import and in `identify()` |
| `Email` | `email` | |
| `Phone` | `phoneNumber` | E.164 |
| `objectId` (CleverTap ID) | `installationId` | A **device** id. Never map it to `externalId` |

Two rules, and the whole migration hangs off them:

1. **Import contacts with `external_id` set from CleverTap's `identity` before you ship the build.**
   Until a contact carries its `external_id`, an `external_id`-only event from the app creates a
   *second* contact for that person instead of finding the imported one.
2. **`identify()` with exactly the value you imported.** If the import used your database's user id
   and the app identifies by email, you get two contacts that only merge by luck.

A contact matched by email or phone with no `externalId` yet **adopts** the one you assert, so a list
imported by email gains its identities as users log in. That's a safety net, not a plan.

---

## Behaviour differences worth knowing before you ship

### Logging in as a different user

CleverTap's `onUserLogin` creates a brand-new profile when the identity differs. Arsel resolves the
identifiers against existing contacts and may **merge** — rules in
[Identity](identity.md#what-happens-on-a-merge), with ambiguous cases refused and logged rather than
guessed at.

Call `reset()` on logout. Without it, the next login can assert a second identity while the first is
still stored.

### Logout is not an opt-out

CleverTap has no equivalent of our durable opt-out. `Arsel.reset()` is logout;
`Arsel.optOut()` is "stop sending me notifications" and is **not resurrected** by a later
registration — registration re-runs on every launch, so a revocation those calls could undo would be
no opt-out at all. Don't wire `optOut()` to your logout button.

### Notification rendering

Both SDKs render from data-only messages, so the shape is familiar. Differences:

| | CleverTap | Arsel |
| --- | --- | --- |
| Action buttons | up to 3 | up to 3 |
| Custom key-value pairs on tap | delivered to your Activity | **not yet** — put metadata in the deep link's query string |
| Rich templates (carousel, rating, timer) | yes | not yet — title, body, image, actions |
| Channel creation | via API or dashboard | default channel at `initialize()`; campaign channel created on demand |

Check your existing campaigns for rich templates before cut-over; they need redesigning as standard
notifications.

### Event and property naming

CleverTap convention is title-case with spaces (`Product viewed`, `Charged`). Nothing stops you
carrying that over, but the migration is the cheapest moment you'll ever have to normalise. If you
change names, change them everywhere at once — a mixed estate of `Product viewed` and
`product.viewed` is two events forever.

Reserved prefixes differ: CleverTap reserves `wzrk_`, we reserve `arsel.`.

### Lifecycle events

CleverTap's `App Installed` is `arsel.app_installed`, with the same once-per-install meaning. The
one difference matters at cut-over: devices that already have your app get **no** install event when
they update to the Arsel SDK — they are seeded silently, because emitting would have reported your
whole installed base as installs on release day. Install-based segments therefore start empty and
fill from cut-over forward.

`App Uninstalled` and `App Version Changed` have no counterpart yet.

### Session events

CleverTap emits `App Launched` and `Session Concluded`. We emit `arsel.session_start` and
`arsel.session_end` with a 30-minute background gap, the end event emitted on the *next* foreground
and backdated. A user who never returns produces no end event — a real difference if you built
reporting on session counts.

### Historical events don't come across

Behavioural segments start accumulating at cut-over. Profile data and push reachability transfer;
event history does not. Segments defined on "did X in the last 90 days" will be empty for 90 days.
Decide what to do about that before launch, not after.

---

## Cut-over sequence

1. **Confirm the Firebase project is unchanged**, and upload its service account to Arsel.
2. **Import contacts** with `external_id`, and devices, per the bulk-import guide. Verify a sample
   across platforms.
3. **Export CleverTap's opt-out state and apply it.** Anyone who opted out there must arrive here
   opted out. This is the one failure with legal consequences.
4. **Define any events your backend also sends** in the dashboard. Client-key events auto-define
   themselves; server-key events do not, and will `404`.
5. **Swap the SDK** in the app build: remove the CleverTap dependency and its manifest entries, add
   `push-fcm`, add `initialize()`, `track()`, `identify()`, `reset()`. Do not ship both.
6. **Verify on a real device** with `Arsel.diagnostics()` and a test push.
7. **Release.** Imported devices are already reachable, so campaigns work immediately — they do not
   wait for users to update. The SDK re-registers each device on first launch, matching on
   `installationId` so nothing duplicates.

## Checklist

- [ ] Same Firebase project; service account uploaded to Arsel
- [ ] Contacts imported with `external_id` = CleverTap `identity`
- [ ] The app's `identify(externalId = …)` uses that same value
- [ ] `objectId` mapped to device identity, never to `externalId`
- [ ] Opt-outs exported and applied
- [ ] `reset()` on logout, `optOut()` only on a real opt-out
- [ ] Rich-template campaigns redesigned as standard notifications
- [ ] Deep links verified with `adb shell am start`
- [ ] Reporting that depended on event history has a plan for the empty window
