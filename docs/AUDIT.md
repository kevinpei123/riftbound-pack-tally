# Audit

Date: 2026-05-26

## App Map

- Model: pure domain types in `model/`, including `RiftboundCard`, `ScannedEntry`, `PackSession`, `BoxSession`, `CardPrice`, and `Variant`.
- Core: OCR, card DB sync/search, pricing, persistence, settings, backup, and quota services.
- Feature: Compose screens and ViewModels for first launch, scanner, quick scan, pack/box, collection, settings, and backup.
- UI: shared navigation, currency formatting, theme, and common composables.
- DI: manual construction through `App.kt`.

## Data Flows

- Sync: `FirstLaunchScreen` or Settings calls `CardDbSync.runFullSync()`, `RiftcodexClient` pages `/cards`, DTOs map to Room `cards`, then `CardDatabase.initFromRoom()` hydrates in-memory lookup maps.
- OCR: `CameraScreen` captures and crops a 5:7 guide bitmap, `OcrService` runs ML Kit with optional Otsu retry, `CardOcrParser` extracts set/number, then `CardDatabase` resolves by collector number before fuzzy name fallback.
- Pricing: Pack/Quick Scan/Collection submit builds `PriceRequest(tcgplayerId, variant)`, `CachedPricingRepository` returns cache hits, JustTCG misses are chunked to <=20 per POST, and successful prices are patched back into Room/session state.
- Collection remove: newest loose scan is removed first; if none exists, `SessionRepository.removeOneByCardVariant()` removes the newest matching pack entry.
- Backup/export: export writes JSON; backup zips DB, sanitized prefs, price cache, and manifest.

## Findings

| Severity | Finding | Affected files | Fix | Proof |
|---|---|---|---|---|
| Critical | `model` depended on `core` and `feature` via `CardPrice`, serializer, and `Variant`, breaking the layer contract. | `model/ScannedEntry.kt`, pricing and scanner imports | Moved `CardPrice`, `InstantIso8601Serializer`, and `Variant` into `model/`; updated imports. | Import scans show no model->core/feature and no core->feature imports; `./gradlew test` passes. |
| Critical | A synced cold start could mount `FirstLaunchScreen` while DataStore still emitted its initial null, causing a duplicate full sync on every restart. | `MainActivity.kt` | Added explicit `SyncGate.Loading` and a Room-hydration loading gate before showing first launch. | Device cold start after synced install: `TotalTime 1598ms`; filtered logcat had no `CardDbSync` or Riftcodex network lines. |
| High | Pricing results were keyed by only `tcgplayerId`, so Standard/Foil/Signature requests for the same card could overwrite each other. | `PricingRepository.kt`, `JustTcgPricingRepository.kt`, `CachedPricingRepository.kt` | Changed `priceMany` to return `Map<PriceRequest, Result<CardPrice>>`. | `PricingRepositoryTest.same tcgplayer id with different variants...` |
| High | `completePack()` could advance to the next pack after a pricing failure. | `PackViewModel.kt` | `submitAndAwait()` now returns success only when every pending entry priced; pack advances only on success. | Unit tests pass; airplane-mode manual retry remains a required QA step. |
| High | Collector number lookup generated bad keys for rows already containing `SET-NUM`, and alt-art suffixes were not preserved from OCR. | `CardDatabase.kt`, `CardOcrParser.kt` | Normalized key building; parser preserves lowercase suffixes and strips signature markers only. | `CardDatabaseTest`, `CardOcrParserTest` |
| High | Bad Riftcodex envelopes or empty responses could strand users if not guarded. | `CardDbSync.kt`, `RiftcodexClient.kt` | Accept `items`, `data`, and `cards`; refuse to commit empty entity lists. | `CardDbSyncTest` covers envelopes, empty response, and network failure preserving DB. |
| Medium | Camera capture/crop work used the main executor and retained bitmaps longer than needed. | `CameraScreen.kt`, `OcrService.kt`, scanner/quickscan VMs | Capture/crop now uses a single background executor; intermediate/captured bitmaps are recycled after use. | Build/lint pass; physical long-session heap profiling still required. |
| Medium | Backup included legacy `prices` cache path while the app uses `prices_v2`. | `BackupRepository.kt` | Backup now zips `cacheDir/prices_v2`; prefs export still excludes API key. | Build pass; manual unzip check still recommended. |
| Medium | Settings and collection copy implied behavior that was not true or was unclear. | `SettingsScreen.kt`, `CollectionScreen.kt` | Removed weekly auto-resync wording; clarified cross-pack remove fallback. | Lint pass and screen review. |
| Medium | The debug APK was too large for the target. | `app/build.gradle.kts` | Added `arm64-v8a` ABI filter for the P30 Pro target. | APK improved from 104.38 MiB to 77.45 MiB; still above the 50 MiB target. |
| Low | Build emits AGP deprecation/configuration warnings. | `gradle.properties`, Gradle plugin wiring | Deferred; unrelated to runtime reliability. | Warnings recorded in `TEST_RUNS.md`. |

## Remaining Test Gaps

- No real Room `MigrationTestHelper` migration suite yet.
- No Compose UI fake-camera/fake-OCR tests yet.
- No macrobenchmark or generated baseline profile module yet.
- Camera permission denial, foil/glare, low-light, 50-card heap, backup unzip, and live JustTCG key flows still need manual device QA.
