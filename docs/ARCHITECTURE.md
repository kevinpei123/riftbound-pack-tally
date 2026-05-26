# Architecture

## High-level

`riftbound-pack-tally` is a single-module Android app organized into three
layers, with manual DI through `App.kt` (no Hilt):

- **`model/`** — pure Kotlin domain types (`RiftboundCard`, `Rarity`, `ScannedEntry`, `PackSession`, `BoxSession`). Some are stateful (PackSession holds a StateFlow of entries); none depend on Android framework.
- **`core/`** — services and repositories. Talks to ML Kit, Room, DataStore, OkHttp. Exposes interfaces that the feature layer consumes.
- **`feature/`** — Compose screens and their ViewModels. One package per top-level destination (`home`, `quickscan`, `scanner`, `pack`, `box`, `collection`, `settings`, `backup`).
- **`ui/`** — composables and providers shared across features: `currency.CurrencyFormatter` + `LocalCurrencyFormatter`, `common.LoadingIndicator`, `nav.AppNav` + `Destination`, `theme.*`.

`App.kt` constructs every repository, the database, the pricing pipeline, and
the quota tracker exactly once, and exposes them via `lateinit var`. ViewModels
either pull from `App` directly (AndroidViewModel) or via a constructor factory.

Card data comes from **Riftcodex** (pulled into Room at first launch). Pricing
comes from **JustTCG** (`POST /v1/cards`, batched ≤20). The `tcgplayer_id`
field on each Riftcodex card is the join key into JustTCG — see
`docs/API_NOTES.md` for the full story.

## Data flow for a single scan (the canonical path)

```
[Camera capture]                                    ─ feature/scanner/CameraScreen
       │
       ▼ Bitmap (cropped to guide rect)
[ScannerViewModel.onCardCaptured]                   ─ feature/scanner/ScannerViewModel
       │
       │  setting.forceOcrPreprocessing? optional preprocess()
       ▼
[OcrService.recognize]                              ─ core/ocr/OcrService
       │
       │  ML Kit Text Recognition (bundled, no GMS)
       │  Auto-retry on Otsu-binarized bitmap if max confidence < 0.5
       ▼ List<TextBlock>
[CardOcrParser.parse]                               ─ core/ocr/CardOcrParser
       │
       │  Regex pass: SET-NUM patterns, NUM/TOTAL fallback
       │  Returns CardIdentifier(collectorNumber, setCode, name)
       ▼
[CardDatabase.lookupByNumber / lookupByNameFuzzy]   ─ core/carddb/CardDatabase
       │
       │  In-memory 950-card map loaded from assets/cards.json at App.onCreate
       │  Levenshtein fuzzy fallback
       ▼ RiftboundCard? (or List<RiftboundCard> for ambiguous)
[ScanResult.Identified(card, confidence)]           ← user picks a variant
       │
       ▼
[ScannerViewModel.recordCard]                       ─ withTimeout(30s)
       │
       ▼
[CachedPricingRepository.price]                     ─ core/pricing
       │       (cache hit returns here; doesn't burn quota)
       │
       ▼ (cache miss)
[QuotaAwarePricingRepository.price]
       │  - if at capacity → fail RateLimitedException
       │  - if cached-only mode → fail CachedOnlyModeException
       │  - else delegate, increment on success
       ▼
[HttpPricingRepository.price]
       │  Retrofit → JustTCG /v1/search?q=…&printing=…
       │  X-API-Key header from settingsRepository
       ▼ Result<CardPrice>
[QuotaTracker.recordNetworkCall]
       │  DataStore (prefs) increments quota_used_<utcDate>
       │  Emits QuotaEvent.NearLimit at ≥80%, PromptConfirm at ≥95%
       ▼
[CachedPricingRepository.write] → disk cache JSON file
       │
       ▼
[PackViewModel.append (or QuickScanViewModel.confirmVariant)]
       │
       ▼
[BoxSession.appendToActivePack (or LooseScanRepository.saveEntry)]
       │
       ▼ updated StateFlows
[Room: SessionDatabase.saveBoxWithPacks (or loose_scans.insert)]
       │
       ▼
[Compose recomposes: Pack/Box/Collection screens reflect new entry]
```

## Module dependency diagram (high level)

```
                              ┌─────────────────┐
                              │  feature/*      │   (Compose UI + VMs)
                              │  ─ scanner      │
                              │  ─ quickscan    │
                              │  ─ pack         │
                              │  ─ box          │
                              │  ─ collection   │
                              │  ─ home         │
                              │  ─ settings     │
                              │  ─ backup       │
                              └────────┬────────┘
                                       │
                              ┌────────▼────────┐
                              │  ui/            │
                              │  ─ nav          │
                              │  ─ currency     │
                              │  ─ common       │
                              │  ─ theme        │
                              └────────┬────────┘
                                       │
        ┌──────────────┬───────────────┼───────────────┬───────────────┐
        │              │               │               │               │
        ▼              ▼               ▼               ▼               ▼
   ┌─────────┐   ┌──────────┐   ┌─────────────┐  ┌──────────┐   ┌─────────┐
   │ model   │   │ core/ocr │   │ core/pricing│  │ core/    │   │ core/   │
   │         │   │          │   │             │  │ persist  │   │ settings│
   │ Card    │   │ OcrSvc   │   │ Cached →    │  │ Room     │   │ DataStore│
   │ Rarity  │   │ Parser   │   │ QuotaAware →│  │ Sess/Loose│   │         │
   │ Entry   │   │          │   │ Http →      │  │ DAOs     │   │         │
   │ Pack/Box│   │          │   │ JustTCG  │  │          │   │         │
   └─────────┘   └──────────┘   └─────────────┘  └──────────┘   └─────────┘
                                       │
                                       ▼
                                 ┌──────────┐
                                 │ core/    │
                                 │ backup   │
                                 │ (uses    │
                                 │ Room +   │
                                 │ settings)│
                                 └──────────┘
```

Arrows point from consumer to producer. No reverse arrows — model never imports from core or feature; core never imports from feature.

## Stateful classes that aren't `data class`es

Two domain classes hold reactive state and so are regular classes:

- **`PackSession`** — `entries: StateFlow<List<ScannedEntry>>` + `runningTotal: StateFlow<Double>`. The `addEntry` / `removeEntry` / `replaceEntry` methods mutate via `MutableStateFlow.value = ...`.
- **`BoxSession`** — `packs: StateFlow<List<PackSession>>` + `grandTotal: StateFlow<Double>`. Owns pack lifecycle (auto-rollover on the 15th scan, explicit `startNextPack`).

`PackViewModel.box: StateFlow<BoxSession>` emits only when a brand-new BoxSession is set (e.g., on `startNewSession`). Within a single BoxSession, mutations flow through the nested StateFlows — UI consumers collect the inner flows independently.

## Persistence

- **Room (`session.db`)** — current schema v3. Real migrations on every bump
  (no destructive fallback). v2 added `loose_scans` for Quick Scan; v3 added
  the `cards` table for Riftcodex-sourced data plus a nullable
  `loose_scans.tcgplayerId` (backfilled by [BackfillJob] on first launch).
- **DataStore Preferences (`settings.preferences_pb`)** — settings + quota counter (date-scoped key) + (later) backup state.
- **Disk file cache (`cacheDir/prices/`)** — one JSON per `(cardId, foil, signature)`, written by `CachedPricingRepository`. TTL configurable 1–48h.

## Reset cascade

User taps Settings → "Reset all data":

1. `SettingsViewModel.resetAll` → `App.resetAll`
2. `sessionDatabase.clearAllTables()` (Room)
3. `cachedPricing.clearCache()` (disk)
4. `settingsRepository.resetAll()` (DataStore — wipes settings AND quota counter)
5. `App._resetEvents.tryEmit(Unit)` (SharedFlow tick)
6. `PackViewModel` and `CollectionViewModel` observe → reset in-memory state
7. User remains in Settings; Pack tab now shows empty session; Collection now shows the empty state.

