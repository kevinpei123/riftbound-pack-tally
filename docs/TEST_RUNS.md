# Test Runs

## 2026-05-28 Pricing And Install Warning Follow-Up

Host: Windows / PowerShell
Device: Huawei VOG-L09 / Android 10 / adb id `4BF0219427000620`

| Command | Result | Notes |
|---|---:|---|
| `adb devices -l` via SDK platform-tools | PASS | Phone visible as `VOG-L09`. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache test lint assembleDebug installDebug` | PASS | Unit tests, lint, debug build, and phone install succeeded after the JustTCG fallback fix. Latest APK is 80,503,816 bytes / 76.77 MiB. |
| `adb shell am start -W -n com.riftbound.packtally/.MainActivity` | PASS | Cold launch `TotalTime 1858ms`. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache --project-prop android.debug.obsoleteApi=true help` | PASS | Remaining legacy variant warnings are emitted by the `kotlin-android` plugin while built-in Kotlin migration is incomplete. |

Notes:

- Removed stale AGP properties that caused several install-time deprecation
  warnings, and migrated Kotlin JVM target config away from deprecated
  `kotlinOptions`.
- `android.builtInKotlin=false` and `android.newDsl=false` remain because this
  AGP/Kotlin plugin combination fails during configuration without them.
- The installed app data did not expose a JustTCG API key, so live Karthus
  pricing could not be exercised from automation. The unit test now covers the
  Karthus-style case where JustTCG only returns a foil variant for a Standard
  request.

## 2026-05-27 Scan Session Redesign

Host: Windows / PowerShell

| Command | Result | Notes |
|---|---:|---|
| `git status --short --branch` | PASS | Started clean except later generated build files. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache :app:compileDebugKotlin --rerun-tasks --stacktrace` | PASS | Forced main Kotlin compile after redesign. 5m34s. Warnings only, then fixed coroutine/icon warnings. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache test` | PASS | Full unit test task returned exit code 0 after adding session/pricing/currency tests. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache lint` | PASS | Lint report generated. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache assembleDebug` | PASS | Rebuilt after `clean`; latest APK is 80,378,705 bytes / 76.66 MiB. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache connectedAndroidTest` | PASS | Ran on VOG-L09 Android 10; no Android test sources, task completed. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache test lint assembleDebug connectedAndroidTest` | PASS | Final rerun after the backup manifest count fix. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache :app:dependencies --configuration debugRuntimeClasspath` | PASS | Dependency report generated. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache clean` | PASS | Explicit clean step completed. |
| `adb devices` via SDK platform-tools | PASS | Device `4BF0219427000620` attached. |
| `adb uninstall com.riftbound.packtally` | PASS | Fresh install QA path. |
| `./gradlew --no-daemon --console=plain --no-configuration-cache installDebug` | PASS | Installed on Huawei VOG-L09 / Android 10. |
| `adb shell am start -W -n com.riftbound.packtally/.MainActivity` | PASS | Fresh cold start `TotalTime 1833ms`; synced cold start after fix `TotalTime 1561ms`. |
| Filtered logcat | PASS | Frankfurter USD->AUD refresh succeeded once, Riftcodex sync completed 1064 cards in about 13.9s, later synced launch made no Riftcodex/currency network calls. |

Notes:

- Initial Gradle invocations timed out because daemon output did not flush before
  the tool timeout; stale Java/Gradle processes were stopped before rerunning.
- A final `adb devices -l` check returned an empty device list, so no additional
  phone smoke run was possible after the final code cleanup.
- No device smoke test has been completed yet for the redesign pass.
- Manual camera/OCR card scans still need physical-card validation.

Date: 2026-05-26
Host: Windows / PowerShell
Device: Huawei VOG-L09, Android 10, ABI `arm64-v8a`, adb id `4BF0219427000620`

## Baseline

| Command | Result | Notes |
|---|---:|---|
| `git status --short --branch` | PASS | Repo was dirty before edits; no commits made. |
| `./gradlew clean` | PASS | 21s baseline; later final run 37s. |
| `./gradlew test` | PASS | Baseline tests passed before changes. |
| `./gradlew lint` | PASS | Lint report generated; Gradle emitted config deprecation warnings. |
| `./gradlew assembleDebug` | PASS | Baseline debug APK was 109,446,612 bytes / 104.38 MiB. |
| `./gradlew :app:dependencies --configuration debugRuntimeClasspath` | PASS | Runtime tree includes ML Kit bundled text recognition, CameraX 1.4.1, Compose 1.7.6, Room 2.7.2, DataStore 1.1.1, OkHttp 4.12.0. |
| `./gradlew tasks --all` search for ktlint/detekt/format | PASS | No ktlint/detekt/format tasks present. |
| `adb devices` | PASS | Device attached. |
| `adb uninstall com.riftbound.packtally` | PASS | Fresh install path available. |
| `./gradlew installDebug` | PASS | Installed on VOG-L09 Android 10. |
| `adb shell am start -W -n com.riftbound.packtally/.MainActivity` | PASS | Baseline fresh cold start `TotalTime 1679ms`; post-sync cold start `1560ms`. |
| Filtered `adb logcat` | PASS | First launch synced 1064 cards in about 14.45s. |

## Final Automated Runs

| Command | Result | Notes |
|---|---:|---|
| `./gradlew clean` | PASS | 45.7s after build-script changes. |
| `./gradlew test lint assembleDebug connectedAndroidTest` | PASS | Final combined verification in 3m54s; report at `app/build/reports/lint-results-debug.html`. |
| Debug APK size check | PASS | Final debug APK 80,378,705 bytes / 76.66 MiB after the scan-session rebuild. |
| `./gradlew :app:dependencies --configuration debugRuntimeClasspath` | PASS | Dependency report generated successfully. |
| `adb uninstall com.riftbound.packtally` | PASS | Fresh install QA run. |
| `./gradlew installDebug` | PASS | Installed final APK. |
| Fresh `adb shell am start -W ...` | PASS | `TotalTime 2726ms`; first launch includes sync gate/setup. |
| Filtered first-launch logcat | PASS | `CardDbSync` fetched 11 pages and completed 1064 cards from 19:23:24.178 to 19:23:38.098, about 13.92s. |
| Synced cold start after launch-gate fix | PASS | `TotalTime 1602ms`; filtered logcat stayed empty for sync/network tags, proving no duplicate full sync. |

## Warnings

- Gradle emits AGP deprecation warnings for legacy DSL/options, including `android.newDsl=false`, `applicationVariants`, `testVariants`, `unitTestVariants`, and deprecated build-feature defaults.
- Debug APK still misses the <50 MiB target. Largest entries are unshrunk dex files plus bundled ML Kit OCR native/model assets. The arm64-only ABI filter reduced size by about 27 MiB.

## Manual QA Not Completed

- Real camera permission grant/deny interaction.
- Bright-light, low-light, foil/signature, glare, blur, and skew OCR with physical cards.
- Rapid switching between Scanner and Quick Scan while camera preview is live.
- 50-card rapid session heap stability in Android Studio Profiler.
- Airplane-mode pricing failure and retry with a real JustTCG key.
- Export JSON and backup zip content inspection on device.
- Currency switch and dark mode visual QA at large text scale.
