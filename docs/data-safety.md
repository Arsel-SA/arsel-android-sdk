# Data collection & Play Data safety

What the SDK collects, what it stores, and how to fill in Google Play's **Data safety** form. Written
so you can answer a security review or a Play review without anyone reading the source.

Everything below is sent **only** to the `baseUrl` you configure. No third-party hosts, no analytics
vendors, no ad networks.

## What the SDK sends

### With every event — `POST /v1/events/send`

| Field | Source |
| --- | --- |
| `event`, `data` | exactly what **you** passed to `track()` |
| `anonymous_id` | a random UUID minted by the SDK |
| `external_id` / `email` / `phone_number` | only if **you** passed them to `identify()` |
| `timestamp` | device clock |

### With device registration — `POST …/push/subscriptions`

| Field | Example | Why |
| --- | --- | --- |
| `installationId` | random UUID | the backend's key for this install |
| `deviceToken` | FCM token | delivery |
| `platform` / `vendor` | `android` / `fcm` | routing |
| `enablementStatus` | `AUTHORIZED` | whether the user allowed notifications |
| `appVersion` | `3.4.1` | support and targeting |
| `osVersion` | `14` | support and targeting |
| `deviceModel`, `deviceManufacturer` | `SM-S911B`, `samsung` | support and targeting |
| `deviceTimezone` | `Asia/Riyadh` (IANA) | send-time localisation |
| `deviceLocale` | `ar-SA` (BCP-47) | language selection |

### With engagements — `POST …/push/engagements`

Message id, event type (`delivered`/`displayed`/`suppressed`/`opened`/`clicked`/`dismissed`),
timestamp, action id, deep link, suppression reason, and the message's signature so the backend can
bind the engagement to the send.

## What it stores on the device

In the SDK's own `SharedPreferences`, excluded from **Auto Backup and device-to-device transfer** —
a device secret or an identity that survived a restore onto a different handset would be worse than
losing it.

| Stored | |
| --- | --- |
| `installationId`, `anonymousId` | random UUIDs, not derived from anything about the device |
| identifiers you passed to `identify()` | cleared by `reset()` |
| `deviceSecret` | issued once at registration |
| FCM token, registration and subscription state | |
| session boundaries | |
| the durable request queue | events and engagements awaiting delivery |

`reset()` rotates the anonymous id and clears the identifiers. Uninstalling clears everything.

## What it does **not** collect

Explicitly, because these are the questions that get asked:

- **No advertising ID.** The SDK never reads GAID/AAID, and does not depend on Play Services Ads.
- **No location.** No `ACCESS_*_LOCATION` permission is declared or requested, and no IP geolocation
  is performed client-side.
- **No contacts, calendar, photos, files, SMS, call logs, microphone or camera.**
- **No installed-app list.** It checks whether `firebase-messaging` is on its **own** classpath —
  not what else is on the device.
- **No fingerprinting.** The ids are random UUIDs, not derived from hardware.
- **No screen or interaction capture.** No session replay, no automatic screen-view tracking.
- **No automatic events** other than `arsel.app_installed`, `arsel.session_start`, `arsel.session_end`
  and `arsel.identify`.

Whatever you put in event properties, the SDK sends. It does not inspect or redact them — see
[the guidance on what not to put in a property](events.md#what-not-to-put-in-a-property).

## Permissions the SDK adds to your manifest

| Permission | Why |
| --- | --- |
| `android.permission.INTERNET` | delivering events and engagements |
| `android.permission.POST_NOTIFICATIONS` | required to show notifications on API 33+ |

Nothing else. No `WAKE_LOCK`, no `RECEIVE_BOOT_COMPLETED`, no location, no storage.

## Filling in Play's Data safety form

The SDK's own contribution, assuming you don't put anything extra in event properties. **Your app's
declaration must also cover whatever you pass to `track()` and `identify()`.**

| Data type | Collected | Shared | Purpose | Optional? |
| --- | --- | --- | --- | --- |
| **Device or other IDs** | Yes | No | App functionality, Analytics, Marketing | Required |
| **App activity** — other actions | Yes | No | Analytics, Marketing | Required |
| **App info & performance** — other | Yes | No | App functionality | Required |
| **Personal info** — email address | Only if you pass it to `identify()` | No | App functionality, Marketing | Your call |
| **Personal info** — phone number | Only if you pass it to `identify()` | No | App functionality, Marketing | Your call |
| **Personal info** — user IDs | Only if you pass `externalId` | No | App functionality, Marketing | Your call |

Notes for the form:

- **"Shared"** means transferred to a *third party*. Arsel is your processor under your contract, so
  data sent to your own Arsel org is **collected**, not **shared** — the same treatment as any
  backend you operate. Confirm this against your own DPA.
- **Encrypted in transit:** yes. HTTPS is enforced at config time; a non-HTTPS `baseUrl` throws.
- **Deletion:** you must offer users a way to request deletion. Arsel's contact-erasure API covers
  the server side; `reset()` covers the device side.

## Consent

The SDK does nothing until `initialize()` is called, and prompts for nothing until you call
`requestNotificationPermission()`. Both are yours to gate:

```kotlin
if (consent.analyticsGranted) {
    Arsel.initialize(this, config)
}
```

If consent is withdrawn later, stop calling `track()` and call `reset()`. For a full stop including
push, call `optOut()` — remembering it is durable and not resurrected by a later registration.

## Subprocessors introduced by push

Push necessarily routes through **Google (FCM)**. That is how Android push works and is not
SDK-specific, but it belongs in your subprocessor list.
