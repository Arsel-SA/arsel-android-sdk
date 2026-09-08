# Arsel Android SDK

Events, identity and push notifications for Android, for [Arsel](https://arsel.sa).

```kotlin
Arsel.initialize(this, ArselConfig.Builder(clientKey, baseUrl).smallIcon(R.drawable.ic).build())

Arsel.track("product.viewed", mapOf("sku" to "A-1023"))
Arsel.identify(externalId = user.id)
```

**Two subsystems, and only one of them needs push.** `track()` and `identify()` work on a handset that has
never granted notification permission, has no FCM token, and doesn't have Firebase on the classpath
at all — a user who declines notifications still has a contact and a behavioural history. Only
delivery needs push.

- `minSdk 23` → `targetSdk 35`. One AAR, version-gated behaviour, no per-version variants.
- **Wrapper, not bundler:** Firebase is `compileOnly`. Your app owns its Firebase version.
- Durable: events and engagements are persisted and drained by WorkManager, surviving process death,
  offline periods and app kills.
- MIT licensed.

> **Stable since 1.0.** The public API and the wire contract are frozen within a major version — a
> breaking change costs every integrator an app-store release, so it waits for `2.0.0`. See
> [CHANGELOG.md](CHANGELOG.md). Releases publish to Maven Central from `v*` tags
> (see [RELEASING.md](RELEASING.md)).

---

## Documentation

| | |
| --- | --- |
| **[Events](docs/events.md)** | Custom events, properties, limits, reserved events, sessions, durability. |
| **[Identity](docs/identity.md)** | Anonymous → identified, the identifier ladder, merges, `reset()` vs `optOut()`. |
| **[Push notifications](docs/push-notifications.md)** | Channels, icons, permission, actions, deep links, engagement. |
| **[API reference](docs/api-reference.md)** | Every method: signature, arguments, threading, when to call it. |
| **[Troubleshooting](docs/troubleshooting.md)** | Symptom → cause → fix, including the OEM-specific ones. |
| **[Data safety](docs/data-safety.md)** | What the SDK collects, and how to fill in Play's Data safety form. |
| **[Migrating from CleverTap](docs/migrating-from-clevertap.md)** | API mapping, identity mapping, and the traps. |
| **[Wire reference](docs/wire-reference.md)** | The endpoints, headers and `arsel_*` payload keys the SDK speaks. |
| **[Changelog](CHANGELOG.md)** | Release notes, including the current wire changes. |

The runnable harness app lives in [`sample/`](sample/). It is its own Gradle build and depends on
the published AAR rather than the modules beside it, so it consumes the SDK exactly as an integrator
would.

## Requirements

| | |
| --- | --- |
| `minSdk` | 23 |
| `compileSdk` / `targetSdk` | 35 |
| Kotlin | 1.9+ |
| Firebase | Your own project. `firebase-messaging` provided by the host app. |
| For events only | No Firebase, no permission, no token needed |

## Install

```kotlin
dependencies {
    implementation("sa.arsel:push-fcm:1.3.0")               // pulls core transitively
    implementation(platform("com.google.firebase:firebase-bom:<ver>"))
    implementation("com.google.firebase:firebase-messaging")    // host owns the Firebase version
}
```

The FCM service, the notification tap Activity, `POST_NOTIFICATIONS` and `INTERNET` are merged in
from the SDK's manifests — the host adds nothing to its own manifest for the drop-in case.

## Integrate

```kotlin
// Application.onCreate()
Arsel.initialize(
    this,
    ArselConfig.Builder(clientKey = BuildConfig.ARSEL_CLIENT_KEY, baseUrl = "https://api.arsel.sa")
        .defaultChannel("arsel_default", "Notifications")
        .smallIcon(R.drawable.ic_stat_notify)
        .build(),
)

Arsel.track("product.viewed", mapOf("sku" to "A-1023", "price" to 149.99))

// On login. Everything tracked beforehand merges onto this contact.
Arsel.identify(externalId = user.id)

// On logout — new anonymous identity; the device stays subscribed.
Arsel.reset()

// Only when the USER asks to stop receiving notifications. Durable; not resurrected by re-registration.
Arsel.optOut()
```

`clientKey` is the org's **publishable** `pub_…` key. It authenticates both APIs and grants nothing
a secret API key does, so it is safe to compile into an APK. **Your secret API key is not** — anyone
can unzip an APK.

`reset()` and `optOut()` are deliberately different calls. Calling `optOut()` on logout would leave
the handset unreachable for the life of the install: a user who signs back in would not receive push
again. See [Identity](docs/identity.md#logout-vs-opt-out).

## Module layout

```
core/   # Firebase-free: facade, config, registry, state, events, network + queue, rendering
push-fcm/    # Firebase glue only: ArselPushService + App Startup bridge + isArselMessage
```

Splitting them is what lets the events API work with no Firebase on the classpath.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/<jdk>     # the directory, not .../bin/java
./gradlew assembleDebug
./gradlew test                          # JVM unit tests
./gradlew publishToMavenLocal           # sa.arsel:core + push-fcm
```

Needs a `local.properties` with `sdk.dir=` (gitignored).

Release and publishing steps are in [RELEASING.md](RELEASING.md).
