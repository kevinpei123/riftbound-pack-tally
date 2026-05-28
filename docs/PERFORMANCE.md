# Performance

Date: 2026-05-27
Device measured: Huawei VOG-L09 / Android 10 / `arm64-v8a` where noted

## Measurements

| Metric | Target | Actual | Status | How measured |
|---|---:|---:|---|---|
| Debug APK size | < 50 MiB | 76.66 MiB | Miss | `Get-Item app/build/outputs/apk/debug/app-debug.apk` = 80,378,705 bytes |
| Baseline debug APK size | < 50 MiB | 104.38 MiB | Baseline | Same command before ABI filter |
| Synced cold start to first activity | < 2s | 1.561s | Pass | `adb shell am start -W -n com.riftbound.packtally/.MainActivity` |
| Fresh-install first launch | < 2s first frame | 1.833s | Pass | Same command after uninstall/install; sync continues after first frame |
| Riftcodex full sync | 5-15s | 13.90s | Pass | Filtered logcat from `Starting Riftcodex full sync` to `Sync complete` |
| Frankfurter USD->AUD refresh | non-blocking | 0.874s | Pass | Filtered logcat for `api.frankfurter.dev/v2/rates` |
| OCR scan to identified | < 3s | Not measured | Open | Needs physical card scan timing |
| Submit 18 session cards | < 4s | Not measured live | Open | Unit test proves one <=20-card POST |
| Submit 46 session cards | < 8s | Not measured live | Open | Unit test proves 20/20/6 chunking |
| Submit all, 100 session cards | < 12s | Not measured live | Open | Unit test proves five <=20-card POST chunks |
| 50-card session heap | Stable | Not measured | Open | Needs Android Studio Profiler or `dumpsys meminfo` sampling |

## Size Notes

Final debug APK contents are dominated by:

- `classes.dex` and split dex files: unshrunk debug dependency graph.
- `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so`: about 9.54 MiB.
- Bundled ML Kit text-recognition models under `assets/mlkit-google-ocr-models`.

The app now filters native libraries to `arm64-v8a`, matching the P30 Pro/VOG-L09 test target. This cut the debug APK by about 27.72 MiB, but the unminified debug build remains above target. Keep the filter in mind if testing on x86/x86_64 emulators.

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
- Current Session and Collection submit are the only user-facing pricing paths.
- Cache hits return immediately and do not call the delegate repository.
- Network misses are chunked to <=20 `PriceRequest` values.
- Variant-specific pricing avoids collapsing Standard/Foil/Signature results for the same `tcgplayer_id`.
- Exchange-rate refresh is separate from pricing and never blocks scan or submit.

## Remaining Work

- Add a Macrobenchmark module for cold start and navigation jank.
- Generate and ship Baseline Profiles once the main flows are stable.
- Add a real heap-stability script or profiler protocol for 50-card rapid sessions.
- Evaluate release minify/resource shrink separately; do not make debug minified until debugging tradeoffs are acceptable.
