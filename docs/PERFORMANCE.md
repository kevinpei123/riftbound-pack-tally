# Performance — Targets and How to Measure

I can't measure these from the build environment (no Android SDK, no device).
The numbers below are TARGETS, not measurements. Fill in the "Actual" column
on first install / box day.

| Metric | Target | Actual | How to measure |
|---|---|---|---|
| APK size (debug) | < 50 MB | — | `ls -lah app/build/outputs/apk/debug/app-debug.apk` |
| Cold start to first frame | < 2 s | — | `adb shell am start -W -n com.riftbound.packtally/.MainActivity` → read `TotalTime` |
| Scan → price (cache hit) | < 4 s | — | Stopwatch from FAB tap to price visible on Saved sheet |
| Scan → price (network) | < 8 s | — | Same, with a fresh card not in cache |
| 50-card session heap | Stable (no monotonic climb) | — | Android Studio Profiler → memory tab during a rapid-fire Quick Scan |

## What I changed that should help (without measuring)

- **Bitmaps recycled** at the boundary between rotate → crop → callback in `CameraScreen.kt:312, 334`. Long sessions shouldn't accumulate captured bitmaps.
- **OCR preprocessing on `Dispatchers.Default`** in `OcrService.recognize` via `withContext`. Doesn't block main during gray+contrast+Otsu.
- **`@ColumnInfo(index = true)` on `LooseScanEntity.cardId`** in `SessionEntities.kt`. Query `WHERE cardId = ?` won't full-scan.
- **Room queries are all suspend** — no main-thread DB calls.
- **DataStore reads are all via Flow + suspend** — no main-thread prefs reads.

## What I deliberately didn't optimize

- `applyOtsuThresholding` does two passes over the pixel array (one to build histogram, one to apply). Combining into a single pass would save ~50% of allocs on the retry path. Not worth the complexity for a 256×384 image — finishes in microseconds anyway.
- Pricing JSON parsing happens per cache-read. Could cache a parsed-Map keyed by card+variant, but the JSON files are tiny (<200 bytes each) and parsing is fast.

## Probable APK-size culprits (run `./gradlew :app:dependencies` to confirm)

1. **ML Kit bundled text recognition model** (~25 MB). Required — bundling avoids the GMS dependency. Acceptable cost.
2. **Compose UI + Material 3 + foundation** (~8 MB combined).
3. **CameraX** (~3 MB).
4. **kotlinx.serialization + kotlinx.coroutines** (~2 MB).

Anything beyond ~50 MB total, dig into the `:dependencies` tree.

## Recomposition smell — manual audit

Spots I looked at:

- `CollectionScreen.LazyColumn.items(group.entries, key = { ... })` — keyed correctly; only the changed row recomposes.
- `PackScreen.LazyVerticalGrid` — uses `items(PackSession.CAPACITY)` with index-based addressing. When an entry changes, only its cell recomposes via the inner `pack.entries.collectAsStateWithLifecycle()`.
- Sticky headers in `LazyVerticalGrid` are scoped to `LazyGridScope.stickyHeader`; they re-render only when the header's own derived state changes.

If you ever notice jank, run `adb shell setprop debug.layout true` to see the layout pass count.
