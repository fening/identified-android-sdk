# Identified Android SDK

Native Kotlin/Compose identity verification for **Android 7.0 / API 24+**.
The SDK runs the same consent, name, ID details, document capture, three-pose
selfie, proof of address, review, status and correction flow as the iOS SDK.

## Install (JitPack)

Add JitPack to the host app's `settings.gradle.kts`, then the dependency:

```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
```

```kotlin
dependencies { implementation("com.github.fening:identified-android-sdk:0.1.0") }
```

The host still declares the Android library, Kotlin Android, Compose compiler,
serialization and Parcelize plugins (the example uses AGP 8.10.1, Kotlin 2.2.21).
The SDK's own dependencies come transitively. A Maven Central release is planned
and will use a different coordinate.

## Validation status

**No physical-device run is on record for Android.** The SDK passes unit tests,
lint and emulator-based connected tests, but liveness and real-document capture
have not been validated on hardware. **iOS has:** a full flow on a physical
iPhone 12 against production on 2026-11-06. Treat Android as unproven on devices
until a hardware run is recorded.

## License

MIT, see `LICENSE`. The bundled DM Sans and Outfit fonts are under the SIL Open
Font License; notices are in `identified-sdk/src/main/assets/identified-fonts`.

## Build the example

Use an installed JDK 21 and Android SDK platform 36. The Gradle wrapper pins the
daemon to JDK 21; it does not silently download a JDK. Set `ANDROID_HOME` or
your ignored `local.properties` SDK path.

```sh
cd mobile/android
./gradlew :identified-sdk:testDebugUnitTest :identified-sdk:lintDebug
./gradlew :identified-sdk:assembleRelease :example:assembleDebug :example:assembleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew :identified-sdk:connectedDebugAndroidTest
python3 scripts/check-copy-parity.py
```

`example` is a separate host application with token and base URL fields. It
defaults to `https://api.identified.cc`. For a local stack on your computer,
use `http://10.0.2.2:8101` from the emulator. Cleartext traffic is enabled only
in the example's Debug manifest; the SDK does not weaken your app's network
security policy.

## Alternative: add the source module

In the host app's `settings.gradle.kts`:

```kotlin
include(":identified-sdk")
project(":identified-sdk").projectDir = file("../identified-android-sdk/identified-sdk")
```

Declare the Android library, Kotlin Android, Compose compiler, serialization,
and Parcelize plugin versions in your root plugin configuration (the runnable
example uses AGP 8.10.1 and Kotlin 2.2.21), and add:

```kotlin
dependencies { implementation(project(":identified-sdk")) }
```

The source module brings its CameraX, Compose, bundled ML Kit, OpenCV, OkHttp,
coroutine and serialization dependencies. A copied AAR alone does not carry
those dependency declarations.

## Launch verification

Your **backend** creates a session using `POST /api/v1/sessions` with
`X-API-Key`. Pass only its `session_token` to the app. Never put tenant API keys
in an Android app. The session determines test versus live; there is no mobile
environment selector.

```kotlin
import cc.identified.sdk.*

private val verification = registerForActivityResult(IdentifiedVerification.Contract()) { result ->
    when (result) {
        is IdentifiedResult.Completed -> showPendingVerification(result.caseId)
        IdentifiedResult.Cancelled -> showResumeOption()
        is IdentifiedResult.Failed -> showVerificationError(result.error)
    }
}

verification.launch(IdentifiedConfiguration(sessionToken = token))
```

Compose hosts can use `rememberLauncherForActivityResult` with the same contract.
The flow activity is internal and non-exported. The merged manifest declares
camera and location permissions; the SDK requests them only when needed.

`Completed(caseId, status)` means the subject submitted, or had already
submitted, and closed Status. It may return `manual_review`,
`background_pending`, or another nonterminal status. **Use the signed webhook
or the server-side case endpoint for the authoritative verification result.**
`return_url` is ignored by native SDKs.

`Failed` carries `SessionInvalid`, `SessionExpired`, `NoCamera`, or
`Unexpected(message)`. Network failures and document refusals offer recovery
inside the flow. Camera denial offers Settings and retry. Create a fresh
session after invalid/expired results.

## Theme the flow

```kotlin
val appearance = Appearance(
    light = Palette.Light.copy(accent = 0xFF4057D6, onAccent = 0xFFFFFFFF),
    dark = Palette.Dark.copy(accent = 0xFF8EAAFF, onAccent = 0xFF102A28),
    fontFamily = "fonts/Inter-Regular.ttf",       // host app asset, or system family name
    headingFontFamily = "fonts/Inter-SemiBold.ttf",
    cornerRadius = 12f,                          // dp
    showsLockup = false,
    colorScheme = ColorSchemePreference.SYSTEM,
)
verification.launch(IdentifiedConfiguration(token, appearance = appearance))
```

Both palettes expose accent/on-accent, background, surface, primary/secondary
text, border, error, success and warning colours. Defaults match iOS. Font files
belong in the host app's `src/main/assets/fonts/` directory. Camera overlays use
high-contrast instructions and inherit the host accent.

## State and privacy

The Activity uses `FLAG_SECURE`. Tokens, form drafts, captured JPEGs and picker
contents stay in memory; the SDK writes no image files or tokens to preferences,
saved instance state, disk caches, logs or the photo library. OkHttp disk cache,
cookies, automatic retries and redirects are disabled.

Launch Intents contain an opaque ID, with configuration handed over through an
in-process registry. Configuration and results are Parcelable for API ergonomics;
the SDK does not put configuration in its Activity Intent or saved-state bundle.
Rotation retains the flow ViewModel and its drafts. If the process dies, the
lost launch returns `SessionInvalid`; the host may relaunch with a valid token
and resume from `sessions/current`. Unuploaded captures must be retaken.

## Testing

Unit tests cover the profile/routing matrix, API envelopes and requests, consent,
metadata, retries, result handling, file bounds and all capture algorithms.
Connected tests run the real Activity/Compose/API client against a local
MockWebServer, with explicit Debug-only synthetic camera injection. The Release
variant has no setter or runtime flag for fixture capture.

For the real local Go gateway/session/orchestrator round trip, start the local
Docker stack, then run:

```sh
python3 scripts/local-backend-smoke.py
```

The script only accepts the loopback Docker stack. It creates and deletes a
dedicated synthetic test tenant, creates a test session, records consent,
saves synthetic name/ID metadata, creates/reads the case and verifies resume.
It does not upload identity images, run providers, send webhooks or use live
credit. The ordinary suite skips this test unless the script supplies its
ephemeral token.

Synthetic images establish capture rules and request ordering, not biometric
accuracy. Physical-device ML Kit/server yaw calibration and real-document
acceptance remain separate acceptance checks, as on iOS.
