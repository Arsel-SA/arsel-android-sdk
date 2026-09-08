# Events

## Tracking

```kotlin
Arsel.track("product.viewed", mapOf("sku" to "A-1023", "price" to 149.99, "in_stock" to true))
```

Needs no push token, no notification permission, and no Firebase on the classpath. An event tracked
before `identify()` attaches to the anonymous identity and is merged forward when the user is
identified.

`track()` returns immediately. It is safe from any thread and it never throws — every public entry
point on `Arsel` is crash-isolated, because a third-party SDK must never take down a host app.

### You do not have to define the event first

An event name that doesn't exist in your org **defines itself** on first receipt from a client key,
with an empty schema. This is deliberate: a shipped APK cannot be redeployed to fix a rejection, so
a name the dashboard hasn't seen is accepted rather than refused.

The server-side API behaves differently — there, an undefined event name is a `404`. If you send the
same event from both your backend and the app, define it in the dashboard first.

Schema mismatches from the app are likewise **recorded, not rejected**: the event is stored with its
validation errors attached, so drift shows up in the dashboard instead of the data disappearing.

## Naming

No convention is enforced, so pick one and hold it. `noun.verb_past` reads well in a segment builder:

```
product.viewed        cart.updated       checkout.started
order.placed          order.refunded     subscription.cancelled
```

Names are **case-sensitive** — `Product.Viewed` and `product.viewed` are two events, forever.

| Rule | |
| --- | --- |
| Max length | 80 characters (truncated, not rejected) |
| Reserved | anything starting `arsel.` — ignored |
| Blank | ignored |

## Properties

```kotlin
Arsel.track(
    "order.placed",
    mapOf(
        "order_id" to "A-1023",     // String
        "total" to 149.99,          // Number
        "currency" to "SAR",
        "is_gift" to false,         // Boolean
        "placed_at" to Date(),      // → toString()
    ),
)
```

| Kotlin type | Sent as |
| --- | --- |
| `String` | as-is |
| `Int`, `Long`, `Double`, `Float` | as-is |
| `Boolean` | as-is |
| anything else | `toString()` — stringified rather than dropped |
| `null` | omitted |

Nested maps and lists are **not** flattened. If you need `items[0].sku` to be queryable, flatten it
yourself or send one event per item.

From Java, the same call takes a `Map<String, Object>`; `properties` is `@JvmOverloads`-optional.

### What not to put in a property

- Passwords, tokens, card numbers, national IDs. Events are long-lived analytical records.
- Whole API responses — they stringify into something nobody can segment on.
- A value that is really an identifier. `identify()` is where identifiers go; a `user_id` property
  binds nothing.

## Reserved events

The SDK emits these itself. Your `track()` cannot create or overwrite them.

| Event | When | Properties |
| --- | --- | --- |
| `arsel.app_installed` | the first launch after this app is installed | `app_version`, `sdk_version`, `platform` |
| `arsel.session_start` | the app comes to the foreground, cold or after 30+ minutes away | — |
| `arsel.session_end` | discovered on the **next** foreground, backdated to when the app left | `duration_seconds` |
| `arsel.identify` | `identify()` supplied at least one identifier | — |
| `arsel.screen_view` | `screen()` was called | `screen_name`, plus whatever you passed |

## Installs

`arsel.app_installed` fires once, on the first launch after the app is installed, ahead of that
launch's `arsel.session_start` so the install leads the timeline. The flag behind it lives in the
SDK's `SharedPreferences`, which Android deletes with the app — so an uninstall and reinstall
correctly counts again, while `reset()` and `optOut()` do not.

One consequence worth knowing before you build a funnel on it:

> **Devices already running an older SDK never get one.** They are seeded silently on their first
> launch after the update. Emitting instead would have reported your entire installed base as
> installs on release day — so "installed in the last 30 days" excludes anyone who installed before
> this event existed.

## Sessions

A session ends after **30 minutes** in the background — the same gap as the web SDK, so a "session"
means one thing across your platforms. Below that, a rotation, a permission dialog or a quick app
switch would read as a session boundary and inflate every count you have.

The end event is emitted on the *next* foreground, backdated to when the app actually left — there is
no timer. A timer would have to survive the process being killed, which is exactly when it matters,
and Android kills backgrounded processes aggressively and without warning.

The consequence, stated plainly because it looks like a bug:

> **A user who leaves and never comes back produces no `arsel.session_end`.** An open-but-unclosed
> session beats a fabricated end time.

The same rule covers a process that dies while foregrounded (a crash, an OEM kill): the end was
never observed, so the stale session is dropped unclosed on the next launch rather than closed with
a duration spanning the dead time.

## Delivery and durability

Events are persisted **before** they are sent, into the same store and the same WorkManager drain
the push engagements use. They survive:

- being offline (they drain when connectivity returns)
- the app being killed or swiped away
- process death
- device reboot

The queue drains oldest-first, **stops at the first retryable failure** so a later event never
overtakes an earlier one, and **discards permanent failures** rather than wedging everything behind
them.

### Retry policy

| Response | Treated as |
| --- | --- |
| `2xx` | delivered |
| `408`, `429`, `5xx` | retryable |
| `404` | retryable — an org whose push channel isn't switched on yet answers this, and giving up would strand an install that would have worked tomorrow |
| no response (offline, DNS, TLS) | retryable |
| `401` / `403` on an authed route | re-auth — the device secret is no longer accepted |
| any other `4xx` | permanent — dropped |

WorkManager owns the *schedule* — when the process is woken — but not the retry timing. After a
retryable failure the wait is 5s, doubling, capped at 5 minutes, plus up to 50% jitter, and is
persisted: WorkManager's own backoff is reset by every enqueue, so without this an active app
retried every ~10s no matter how hard the server pushed back. A `Retry-After` raises the wait but
never lowers it. A wakeup that arrives during the wait does no network. Drains are also triggered by
`initialize()`, by app foreground, and by `flushNow()`.

```kotlin
Arsel.flushNow()   // QA and integration proofs; not needed in normal operation
```

## Limits

| | Limit | Over the limit |
| --- | --- | --- |
| Event name | 80 chars | truncated |
| `anonymous_id` | 128 chars | truncated |
| `external_id` | 255 chars | truncated |
| Properties per event | no hard client limit | — |

The SDK truncates rather than rejects, because the API's validation caps are the same numbers and a
`400` from a shipped APK is unfixable from your side.

## Debugging

```kotlin
ArselConfig.Builder(clientKey, baseUrl)
    .logLevel(LogLevel.DEBUG)   // default is WARN
    .build()
```

```kotlin
Log.d("MyApp", Arsel.diagnostics().toString())
```

`eventQueueDepth` is reported **separately** from the total `queueDepth`, because the two fail
independently: push can be perfectly healthy while every event bounces off a bad client key. A depth
that only grows means delivery is failing — check `lastResponseCode` against the table above.
