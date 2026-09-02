# Wire reference

The contract between this SDK and the Arsel API, as this SDK version implements it. This is the
authoritative in-repo description; changes are recorded in the
[changelog's Wire sections](../CHANGELOG.md). Everything here is observable from a device running
the SDK — there is nothing to configure beyond `clientKey` and `baseUrl`.

## Endpoints

All under the configured `baseUrl`. Every request carries `X-Arsel-SDK: android/<sdkVersion>`.

| Call | Auth |
| --- | --- |
| `POST /v1/events/send` | `Authorization: Bearer <clientKey>` + `Idempotency-Key` |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions` | none on create; the response mints the device secret |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions/state` | `X-Arsel-Device-Auth` |
| `POST /api/v1/orgs/{clientKey}/push/subscriptions/unsubscribe` | `X-Arsel-Device-Auth` |
| `POST /api/v1/orgs/{clientKey}/push/engagements` | `X-Arsel-Device-Auth` |

`{clientKey}` is the org's publishable `pub_…` key — the same value passed to `ArselConfig`.

## Registration

`POST …/push/subscriptions` sends:

- `installationId` — the SDK's stable per-install id (survives FCM token rotation)
- `deviceToken` — the FCM registration token
- `platform: "android"` and `vendor: "fcm"` — device family vs transport; an iOS row may carry
  either an FCM or an APNs token, which is why these are separate fields
- `anonymousId` — the device→contact binding, resolved server-side through the same identifier
  ladder the events API uses, so events and push land on the same contact
- Device facts: `enablementStatus` (`AUTHORIZED` / `DENIED` / `NOT_DETERMINED`), `appVersion`,
  `osVersion`, `deviceModel`, `deviceManufacturer`, `deviceTimezone` (IANA), `deviceLocale` (BCP-47)

The first successful create returns a `deviceSecret`, issued exactly once. The SDK persists it and
presents it as `X-Arsel-Device-Auth` on every subsequent mutation; an installation that loses it
cannot authenticate again (clear app data to mint a new installation).

`POST …/subscriptions/state` reports permission changes and FCM token rotations.

## Events

`POST /v1/events/send` carries custom events and the SDK's own `arsel.app_installed` /
`arsel.session_start` / `arsel.session_end` (the `arsel.` name prefix is reserved). Each send
carries an `Idempotency-Key` header — the queued request's persisted id, identical across retries (24-hour server window) — so a
retry after a lost acknowledgement cannot double-count.

## Inbound push payload

An Arsel push is a **data-only FCM message with `priority: high`** — no `notification` block. Every
value is a string (an FCM requirement). The SDK claims a message on `arsel_v` (falling back to
`arsel_mid`); keys:

| Key | |
| --- | --- |
| `arsel_v` | wire version; the claim marker |
| `arsel_mid` | message id, echoed on every engagement |
| `arsel_sig`, `arsel_kid` | signature + key id, echoed on engagements; without them engagements count for engagement but not attribution |
| `arsel_title`, `arsel_body` | notification content |
| `arsel_image` | optional image URL |
| `arsel_deep_link` | optional tap target |
| `arsel_channel_id` | optional notification channel override |
| `arsel_actions` | optional JSON-encoded array of `{actionId, label, deepLink}` |
| `arsel_collapse_id` | optional collapse key |

Unknown `arsel_*` keys are ignored (forward compatibility). Non-`arsel_` keys can never shadow
`arsel_*` ones and are preserved verbatim as `ArselPushMessage.hostData`.

## Engagements

`POST …/push/engagements` takes a batch envelope: `{ installationId, events: [ … ] }`, max 50 events.
Event types: `delivered`, `displayed`, `suppressed` (with a reason), `opened` (body tap), `clicked`
(action button, with `actionId`), `dismissed`. Exactly one of `opened`/`clicked` fires per tap. Each
event echoes `arsel_mid` and, when present, `arsel_sig`/`arsel_kid`. Taps flush immediately because
they drive automation triggers; delivery signals coalesce briefly. Engagement `deepLink` values are
truncated to 2048 characters. The server deduplicates on `(messageId, eventType, subscriptionId)`.
