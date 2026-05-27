# Performance

Date: 2026-05-26
Device measured: Huawei VOG-L09 / Android 10 / `arm64-v8a`

## Measurements

| Metric | Target | Actual | Status | How measured |
|---|---:|---:|---|---|
| Debug APK size | < 50 MiB | 77.45 MiB | Miss | `Get-Item app/build/outputs/apk/debug/app-debug.apk` |
| Baseline debug APK size | < 50 MiB | 104.38 MiB | Baseline | Same command before ABI filter |
| Synced cold start to first activity | < 2s | 1.598s | Pass | `adb shell am start -W -n com.riftbound.packtally/.MainActivity` |
| Fresh-install first launch | < 2s first frame | 2.726s | Miss | Same command after uninstall/install; includes first-launch setup path |
| Riftcodex full sync | 5-15s | 13.92s | Pass | Filtered logcat from `Starting Riftcodex full sync` to `Sync complete` |
| OCR scan to identified | < 3s | Not measured | Open | Needs physical card scan timing |
| Submit pack, 14 cards | < 4s | Not measured live | Open | Unit test proves one POST; live key/device timing still needed |
| Submit all, 100 loose scans | < 12s | Not measured live | Open | Unit test proves five <=20-card POST chunks |
| 50-card session heap | Stable | Not measured | Open | Needs Android Studio Profiler or `dumpsys meminfo` sampling |

## Size Notes

Final debug APK contents are dominated by:

- `classes.dex` and split dex files: unshrunk debug dependency graph.
- `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so`: about 9.54 MiB.
- Bundled ML Kit text-recognition models under `assets/mlkit-google-ocr-models`.

The app now filters native libraries to `arm64-v8a`, matching the P30 Pro/VOG-L09 test target. This cut the debug APK by about 27.29 MiB, but the unminified debug build remains above target. Keep the filter in mind if testing on x86/x86_64 emulators.

## Startup Notes

- A launch-gate race previously caused a synced cold start to briefly show `FirstLaunchScreen` and start a duplicate Riftcodex sync before DataStore emitted `cards_synced_at`.
- `MainActivity` now distinguishes `SyncGate.Loading` from `Loaded(null)` and shows a loading gate while Room hydrates the in-memory `CardDatabase`.
- After the fix, synced cold start was 1.602s and filtered logcat showed no `CardDbSync` or Riftcodex network lines.

## OCR And Camera Notes

- Camera crop/rotate work runs on a single background executor rather than the main executor.
- `CameraScreen` tracks owned CameraX use cases and unbinds only those on dispose.
- `ResolutionSelector` with 16:9 fallback replaced deprecated target-aspect configuration.
- Captured and intermediate preprocessed bitmaps are recycled after OCR use.
- OCR preprocessing remains retry-based; the normal path avoids extra grayscale/Otsu cost unless confidence is weak or forced in settings.

## Pricing Notes

- Recording scans is local-only and never calls pricing.
- Pack submit and Quick Scan/Collection submit are the only pricing paths.
- Cache hits return immediately and do not call the delegate repository.
- Network misses are chunked to <=20 `PriceRequest` values.
- Variant-specific pricing avoids collapsing Standard/Foil/Signature results for the same `tcgplayer_id`.

## Remaining Work

- Add a Macrobenchmark module for cold start and navigation jank.
- Generate and ship Baseline Profiles once the main flows are stable.
- Add a real heap-stability script or profiler protocol for 50-card rapid sessions.
- Evaluate release minify/resource shrink separately; do not make debug minified until debugging tradeoffs are acceptable.
