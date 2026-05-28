# Research Notes

Date: 2026-05-26

## Sources

- Android CameraX lifecycle/API reference: https://developer.android.com/reference/androidx/camera/lifecycle/LifecycleCameraProvider
- Android CameraX release/configuration notes: https://developer.android.com/jetpack/androidx/releases/camera
- ML Kit text recognition on Android: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Compose stability/performance: https://developer.android.com/develop/ui/compose/performance/stability
- Android Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/overview
- Android Macrobenchmark overview: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Room migration testing helper: https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper
- DataStore architecture docs: https://developer.android.com/topic/libraries/architecture/datastore
- OkHttp MockWebServer docs/source: https://square.github.io/okhttp/
- JustTCG `/cards` docs: https://justtcg.com/docs/api/cards
- Riftcodex card schema: https://riftcodex.com/docs/riftcodex/schemas/card
- TCGplayer scanning tips: https://help.tcgplayer.com/hc/en-us/articles/115009674788-Tips-for-Accurate-Scanning
- TCGplayer app scanner FAQ: https://help.tcgplayer.com/hc/en-us/articles/115009506407-TCGplayer-App-FAQ
- ManaBox scanner FAQ: https://www.manabox.app/guides/scanner/faq/
- Dragon Shield scanner app listing: https://apps.apple.com/us/app/fab-scanner-dragon-shield/id1619340476
- Frankfurter exchange-rate API docs: https://frankfurter.dev/docs/

## Decisions From Research

- Keep CameraX lifecycle binding scoped to the screen, and unbind only the use cases this screen owns. This follows the lifecycle-bound CameraX model and reduces black-screen risk when switching tabs.
- Use a capture executor for bitmap crop/rotate work. ML Kit and image preprocessing should not run on the main thread.
- Prefer `ResolutionSelector` over deprecated target-aspect APIs. The app now uses a 16:9 strategy for capture while the UI guide remains 5:7 for card crop alignment.
- ML Kit accuracy depends heavily on focus, resolution, and rotation. The app keeps a high-quality still capture path, preserves rotation handling, and retries OCR with grayscale/contrast/Otsu only when confidence is weak.
- Collector number should be the primary lookup key. High-end card scanner docs call out wrong-set risk when art is reused, so OCRing `SET NUM/TOTAL` is safer than image/name matching for Riftbound.
- Keep manual correction fast and visible. Scanner app UX research consistently points to variant/set ambiguity; the app keeps correction and manual add paths rather than hiding uncertainty.
- Pricing remains explicit-submit only. JustTCG supports batch POST and plan-dependent batch caps; this app conservatively keeps chunks at <=20 to match free-tier constraints and protect quota.
- Room migrations should eventually be covered with `MigrationTestHelper`; current unit tests cover repository behavior but not SQLite schema migration paths.
- DataStore should stay behind a repository. Tests added in this pass avoid live DataStore where possible; future DataStore tests should use a temp file/fake repository.
- Baseline Profiles/Macrobenchmark are useful for startup and jank, but adding a benchmark module is deferred because this pass already found and fixed a duplicate-sync startup bug first.
- Do not copy proprietary scanner UI/assets. UX changes are limited to clearer states, debug diagnostics, and reducing confusing copy.
- Use Frankfurter for currency conversion because it is public, no-key, and supports latest USD-to-target rates through `api.frankfurter.dev`. Cache rates in DataStore and never block app workflows on currency refresh.
