# Feature Decisions

Date: 2026-05-26

## 2026-05-27 Redesign

- Replaced Pack/Box as the user-facing workflow with scan sessions.
  - Why: pack order and box structure do not match the intended real-world scanning pattern.
  - Outcome: Home, Scan, Current, Collection, Settings are the only bottom-nav destinations.

- Kept legacy Pack/Box tables for migration.
  - Why: dropping them in the same release would risk user data loss.
  - Outcome: old rows can migrate to completed scan sessions; legacy UI is not routed.

- Added automatic Frankfurter exchange-rate refresh.
  - Why: hard-coded manual rates drift and make displayed value unreliable.
  - Outcome: no-key USD-to-target rates are cached; offline/stale fallback is non-blocking.

- Persisted Riftcodex domains.
  - Why: Collection filtering by color/domain is useful and the metadata is already present upstream.

## Added

- Debug-only OCR logging setting.
  - Why: gives raw OCR text, parsed candidate, lookup path, and candidate details during development without exposing noisy diagnostics in release behavior.
  - Scope: shown only when `BuildConfig.DEBUG` is true.

- Safer startup gate.
  - Why: prevents a synced install from starting a full Riftcodex sync while DataStore is still loading.
  - Outcome: normal cold start no longer emits sync/network logs.

- Variant-aware pricing results.
  - Why: the same `tcgplayer_id` can be priced as Standard, Foil, or Signature; keying only by id was wrong.
  - Outcome: pricing maps by `PriceRequest`.

- Arm64-only debug packaging.
  - Why: the target physical device is a Huawei P30 Pro/VOG-L09, which is `arm64-v8a`; dropping unused ABIs cuts sideload APK size.
  - Tradeoff: x86/x86_64 emulators cannot install this debug APK unless the ABI filter is temporarily removed.

- Clearer failed-scan and collection copy.
  - Why: box-day UI should say what recovery action is available and what remove will do.

## Preserved

- Pricing stays explicit-submit only. Recording a scan does not call JustTCG.
- JustTCG calls stay batched and chunked to <=20.
- `tcgplayer_id` remains the only pricing join key.
- Cards missing `tcgplayer_id` are dropped from the pricing catalogue rather than shown as priceable.
- Manual DI through `App.kt` remains unchanged.
- No API keys are hardcoded, exported, or written into backup prefs.

## Simplified Or Corrected

- Settings no longer claims weekly background card DB resync, because no WorkManager resync is implemented.
- Collection remove confirmation now states the actual loose-first, newest-pack-fallback behavior.
- Backup now includes the active `prices_v2` cache instead of the legacy `prices` path.

## Deferred

- RiftScribe backup source.
  - Reason: not reliable/tested enough to expose as working behavior.

- Restore flow and automatic backup.
  - Reason: backup exists, but restore needs careful data-integrity and API-key handling tests before it is safe.

- Full Compose UI test suite with fake camera/OCR.
  - Reason: requires test seams for camera/OCR injection; current pass focused on core correctness and device startup reliability.

- Room `MigrationTestHelper` suite.
  - Reason: the repository behavior is covered, but true schema migration tests need exported schemas/test DB assets.

- Macrobenchmark and Baseline Profile module.
  - Reason: useful next step, but fixing the duplicate-sync startup path produced the larger immediate reliability gain.

- Debug APK minification.
  - Reason: would reduce size but make debug builds harder to inspect. Release shrink/minify should be evaluated separately.
