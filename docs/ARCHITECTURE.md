# Architecture

## High-level

`riftbound-pack-tally` is a single-module Android app organized into three
layers, with manual DI through `App.kt` (no Hilt):

- **`model/`** — pure Kotlin domain types (`RiftboundCard`, `Rarity`, `ScannedEntry`, `PackSession`, `BoxSession`). `ScannedEntry.price` is `CardPrice?` — null means "not priced yet, waiting for the next batch Submit". Some types are stateful (PackSession holds a `StateFlow` of entries); none depend on Android framework.
- **`core/`** — services and repositories. Talks to ML Kit, Room, DataStore, OkHttp, Riftcodex, JustTCG. Exposes interfaces that the feature layer consumes.
- **`feature/`** — Compose screens and their ViewModels. One package per top-level destination (`home`, `firstlaunch`, `quickscan`, `scanner`, `pack`, `box`, `collection`, `settings`, `backup`).
- **`ui/`** — composables and providers shared across features: `currency.CurrencyFormatter` + `LocalCurrencyFormatter`, `common.LoadingIndicator`, `nav.AppNav` + `Destination`, `theme.*`.

`App.kt` constructs every repository, the database, the pricing pipeline, and
the quota tracker exactly once, and exposes them via `lateinit var`. ViewModels
either pull from `App` directly (AndroidViewModel) or via a constructor factory.

Card data comes from **Riftcodex** (pulled into Room at first launch, list
endpoint envelopes under `items`). Pricing comes from **JustTCG**
(`POST /v1/cards`, batched ≤20). The `tcgplayer_id` field on each Riftcodex
card is the join key into JustTCG — see `docs/API_NOTES.md` for the full story.

**Pricing is batched, not per-scan.** Recording a scanned card never makes a
network call. The Pack tab's Submit button and the Quick Scan / Collection
"Submit N cards for pricing" buttons are the only paths that hit JustTCG, and
they batch every pending entry in one request (or chunks of ≤20 if there are
more).

## Data flow for a single scan (priceless path)

```
[Camera capture]                                    ─ feature/scanner/CameraScreen
       │
       ▼ Bitmap (cropped to 5:7 card-shaped guide rect)
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
       │  Regex: SET[sep]NUM[letter*]?(/TOTAL)?
       │   - sep = space / period / middle-dot / bullet / hyphen / nothing
       │   - letter = alt-art suffix (preserved in output)
       │   - * = signature marker (absorbed, stripped from output)
       │  Returns CardIdentifier(collectorNumber, setCode, name)
       ▼
[CardDatabase.lookupByNumber / lookupByNameFuzzy]   ─ core/carddb/CardDatabase
       │
       │  In-memory map loaded from Room at MainActivity gate (~1,064 cards)
       │  Levenshtein fuzzy fallback
       ▼ RiftboundCard? (or List<RiftboundCard> for ambiguous)
[ScanResult.Identified(card, confidence)]           ← user picks a variant
       │                                              (or auto-STANDARD if Rapid Mode is on)
       ▼
[ScannerViewModel.recordCard]                       ← NO PRICING CALL
       │
       ▼
[PackViewModel.append (or QuickScanViewModel.confirmVariant)]
       │
       ▼
[BoxSession.appendToActivePack (or LooseScanRepository.saveEntry)]
       │      with price = null
       ▼
[Room: SessionDatabase.saveBoxWithPacks (or loose_scans.insert with priceJson = ""])
       │
       ▼
[Compose recomposes: Pack/Box/Collection rows show the card with "—" for price]
```

## Data flow for a batched Submit

```
[User taps Submit on Pack screen header]
              or
[User taps "Submit N cards for pricing" on Quick Scan / Collection]
       │
       ▼
[PackViewModel.submitPack / completePack]           ─ feature/pack/PackViewModel
                                  OR
[LooseScanRepository.submitPendingPrices(pricing)]  ─ core/persistence
       │
       │  Gather every pending entry → List<PriceRequest>
       ▼
[CachedPricingRepository.priceMany]                 ─ core/pricing
       │       (cache hits return inline; don't burn quota)
       │
       ▼ (cache misses only)
[JustTcgPricingRepository.priceMany]                ─ chunks into batches of ≤20
       │  - Quota gate: at-capacity / cached-only-mode → fail every request in chunk
       │  - At 7/10 of the minute bucket: 6s back-off
       │  - POST /v1/cards with [{tcgplayerId, condition, printing}, ...]
       │  - On 200: recordNetworkCall(1) + applyServerHints(metadata)
       ▼ Map<PriceRequest, Result<CardPrice>>
[CachedPricingRepository] writes successful results to disk cache JSON files
       │
       ▼
[Caller patches each entry's price in-place via pack.replaceEntry / looseScans.setPrice]
       │
       ▼
[Compose recomposes: prices visible. Pack advances if Submit&Complete was tapped.]
```

## Cross-pack remove (Collection)

```
[User taps the per-row "−" icon on a Collection entry]
       │
       ▼
[CollectionViewModel.removeOne(card, variant)]
       │
       ▼
[LooseScanRepository.deleteOneMatching(cardId, variant)]
       │  - newest-first by scannedAt DESC
       │  - returns true if a loose row was deleted
       │
       │  (if no loose row matched)
       ▼
[SessionRepository.removeOneByCardVariant(cardId, variantName)]
       │  - walks every BoxSession (newest-first)
       │  - within each box, walks packs reversed (latest pack first)
       │  - finds first matching ScannedEntry, removes it, saves box
       ▼
[CollectionEvent.RemoveSucceeded(name)] → toast
[CollectionViewModel.refresh()] → list redraws
[PackViewModel.refreshFromDisk() fires on next entry to Pack tab]
```

## Module dependency diagram

```
                              ┌─────────────────┐
                              │  feature/*      │   (Compose UI + VMs)
                              │  ─ home         │
                              │  ─ firstlaunch  │
                              │  ─ quickscan    │
                              │  ─ scanner      │
                              │  ─ pack         │
                              │  ─ box          │
                              │  ─ collection   │
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
        ┌──────────┬───────────────┬───┴───┬─────────────────┐
        │          │               │       │                 │
        ▼          ▼               ▼       ▼                 ▼
   ┌────────┐ ┌──────────┐ ┌─────────────┐ ┌──────────┐ ┌──────────┐
   │ model  │ │ core/ocr │ │ core/pricing│ │ core/    │ │ core/    │
   │        │ │          │ │             │ │ persist  │ │ carddb   │
   │ Card   │ │ OcrSvc   │ │ Cached →    │ │ Room     │ │ DbSync   │
   │ Rarity │ │ Parser   │ │ JustTcg     │ │ Sessions │ │ Database │
   │ Entry  │ │          │ │ Mock        │ │ Loose    │ │ Riftcodex│
   │ Pack/  │ │          │ │ QuotaTracker│ │ Cards    │ │ Client   │
   │  Box   │ │          │ │             │ │ Backfill │ │          │
   └────────┘ └──────────┘ └─────────────┘ └──────────┘ └──────────┘
                                                  │
                                                  ▼
                                            ┌──────────┐
                                            │ core/    │
                                            │ backup   │
                                            └──────────┘

                                  ┌────────────────┐
                                  │ core/settings  │   used by everything
                                  └────────────────┘
```

Arrows point from consumer to producer. Model never imports from core or
feature; core never imports from feature.

## Stateful classes that aren't `data class`es

Two domain classes hold reactive state and so are regular classes:

- **`PackSession`** — `entries: StateFlow<List<ScannedEntry>>` + `runningTotal: StateFlow<Double>`. `addEntry` / `removeEntry` / `replaceEntry` mutate via `MutableStateFlow.value = ...`. The running total uses `ScannedEntry.marketPrice` (which gives `0.0` for unpriced entries).
- **`BoxSession`** — `packs: StateFlow<List<PackSession>>` + `grandTotal: StateFlow<Double>`. Owns pack lifecycle (auto-rollover on the 15th scan, explicit `startNextPack`). `recomputeGrandTotalPublic()` is exposed so PackViewModel can refresh the grand total after a Submit patches prices into individual `PackSession`s (which BoxSession doesn't otherwise observe).

`PackViewModel.box: StateFlow<BoxSession>` emits only when a brand-new
BoxSession is set (e.g., on `startNewSession`). Within a single BoxSession,
mutations flow through the nested StateFlows — UI consumers collect the inner
flows independently.

`PackViewModel.refreshFromDisk()` is called from `PackScreen` on every entry
so edits made from Collection (cross-pack remove) show up here without a
restart.

## Persistence

- **Room (`session.db`)** — current schema v3 (Room 2.7.2 + KSP 2.2.10-2.0.2).
  Real migrations on every bump (no destructive fallback). v2 added
  `loose_scans` for Quick Scan; v3 added the `cards` table for
  Riftcodex-sourced data plus a nullable `loose_scans.tcgplayerId`
  (backfilled by `BackfillJob` on first launch). `loose_scans.priceJson` is a
  non-null TEXT column — empty string is the sentinel for "not yet priced",
  which lets us avoid another schema bump.
- **DataStore Preferences (`settings.preferences_pb`)** — settings + quota counter (date-scoped key) + `cards_synced_at` timestamp + `backfill_v3_completed_at` flag.
- **Disk file cache (`cacheDir/prices_v2/`)** — one JSON per `(tcgplayerId, variant)`, written by `CachedPricingRepository`. TTL configurable 1–48h.

## First-launch / sync gate

`MainActivity` shows `FirstLaunchScreen` whenever any of these is true:

```
lastSyncedAt == null  ||  !CardDatabase.isReady()  ||  CardDatabase.size == 0
```

The third condition is the self-heal: a build that "succeeded" but wrote zero
cards (schema drift, envelope mismatch) gets re-routed to the sync screen
instead of stranding the user. `CardDbSync.runFullSync()` also throws on an
empty result to prevent that state in the first place.

## Reset cascade

User taps Settings → "Reset all data":

1. `SettingsViewModel.resetAll` → `App.resetAll`
2. `sessionDatabase.clearAllTables()` (Room)
3. `cachedPricing.clearCache()` (disk)
4. `settingsRepository.resetAll()` (DataStore — wipes settings AND quota counter AND cards_synced_at)
5. `App._resetEvents.tryEmit(Unit)` (SharedFlow tick)
6. `PackViewModel` and `CollectionViewModel` observe → reset in-memory state
7. MainActivity's gate re-fires `FirstLaunchScreen` (cards.size == 0 after clearAllTables).
