# Test Runs

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
| Debug APK size check | PASS | Final debug APK 81,211,212 bytes / 77.45 MiB. |
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
