# Architecture

## Layers

- `model/`: pure Kotlin domain types. No Android, core, or feature
  dependencies.
- `core/`: services and repositories for Room, DataStore, OCR, pricing, sync,
  currency rates, backup.
- `feature/`: Compose screens and ViewModels.
- `ui/`: shared navigation, theme, and currency formatting.

Manual DI lives in `App.kt`. Hilt is not used.

## Main Data Flow

```text
CameraScreen
  -> ScannerViewModel
  -> OcrService / CardOcrParser
  -> CardDatabase lookup
  -> SessionRepository.addEntry(...)
  -> Room scan_session_entries
```

Recording a scan is local only. It does not call JustTCG.

## Submit Pricing Flow

```text
Current Session / Collection Submit
  -> SessionRepository.submitPendingPrices(...)
  -> chunks pending entries into groups of 20
  -> CachedPricingRepository.priceMany(...)
  -> cache hits return locally
  -> JustTcgPricingRepository.priceMany(cache misses)
  -> each network chunk is one POST and one quota tick
  -> ScanSessionEntry priceJson + pricingStatus updated
```

The repository marks blank `tcgplayerId` rows as `UNPRICEABLE`. Batch failures
leave rows as `FAILED` and retryable.

## Room Schema

Current DB version: `4`.

- Legacy tables retained: `box_sessions`, `pack_sessions`, `loose_scans`.
- Card catalogue: `cards`, now including `domains`.
- New workflow:
  - `scan_sessions`
  - `scan_session_entries`

`MIGRATION_3_4` creates session tables, adds `cards.domains`, and SQL-copies
legacy loose scans into a completed session named `Migrated loose scans`.
Startup migration then decodes legacy `pack_sessions.entriesJson` into
completed scan sessions. Legacy tables are not dropped.

## Navigation

Bottom navigation routes:

- Home
- Scan
- Current
- Cards
- Set

Pack and Box routes and feature screens are removed. Legacy model/entities
remain only as migration support until a future cleanup can safely drop old
tables.

## Currency

JustTCG prices are stored as USD. `CurrencyFormatter` converts for display
using `AppSettings.usdToTargetRate`.

`CurrencyRateRepository` refreshes USD-to-target rates through
`FrankfurterCurrencyRateService`, caches rate metadata in DataStore, and never
blocks scan, collection, or pricing if refresh fails.

## Reset

`App.resetAll()` clears Room tables, price cache, settings/DataStore, and emits
a reset event. After reset, `MainActivity` routes through first-launch sync
because the card table is empty.
