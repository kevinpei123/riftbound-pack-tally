# Scan Session Redesign

Date: 2026-05-27

## Goal

Replace the Pack/Box workflow with a single scan-session list workflow:

- Start or continue one active scan session.
- Every scan/manual add appends to one running list.
- Review, edit, remove, undo, clear, and complete the session.
- Submit pending prices explicitly, batched in chunks of up to 20.
- Collection aggregates all session entries and keeps sorting/filtering useful.

## Pack/Box Dependency Map

User-facing Pack/Box concepts currently appear in:

- Navigation: `Destination.Pack`, `Destination.Box`, `AppNav` composables.
- Screens/ViewModels: `feature/pack/*`, `feature/box/BoxScreen.kt`, `HomeScreen`, `ScannerViewModel`, `ScannerScreen`, `CollectionViewModel`, `CollectionScreen`.
- Model: `PackSession`, `BoxSession`, `ScannedEntry` comments and semantics.
- Persistence: `box_sessions`, `pack_sessions`, `SessionDao`, `SessionRepository`, `SessionDatabase` v1-v3 migrations.
- Backup/export: `BackupRepository` counts `pack_sessions`; Collection export includes `loose_scans`.
- Docs/tests: README, architecture/API/performance/QA/smoke/troubleshooting docs, existing pricing and OCR tests.

Non-Pack concepts to preserve:

- `ScannedEntry` as a generic scanned card value object.
- `Variant`, `CardPrice`, `RiftboundCard`.
- Riftcodex sync and `CardDatabase`.
- JustTCG `PricingRepository`, `CachedPricingRepository`, `QuotaTracker`.
- Camera/OCR pipeline.

## New Data Model

Add:

- `ScanSessionEntity`
  - `id`
  - `createdAt`
  - `completedAt`
  - `name`
  - `status`: `ACTIVE`, `COMPLETED`, `ARCHIVED`

- `ScanSessionEntryEntity`
  - `id`
  - `sessionId`
  - `cardId`
  - `tcgplayerId`
  - `variant`
  - `priceJson`
  - `pricingStatus`: `PENDING`, `PRICED`, `FAILED`, `UNPRICEABLE`
  - `pricingError`
  - `scannedAt`
  - `source`: `OCR`, `RAPID`, `MANUAL`, `MIGRATED_PACK`, `MIGRATED_LOOSE`
  - `confidence`
  - `manuallyCorrected`
  - `notes`

Add optional `domain` metadata to `cards` so Collection can filter/sort/group by Color/Domain when Riftcodex provides `classification.domain[]`.

## Migration Plan

Room version moves from v3 to v4.

1. Create `scan_sessions` and `scan_session_entries`.
2. Add nullable `cards.domain` column.
3. Keep legacy `box_sessions`, `pack_sessions`, and `loose_scans` tables in place.
4. SQL-migrate `loose_scans` into one completed session named `Migrated loose scans`.
5. Kotlin startup migrator reads legacy `box_sessions` + `pack_sessions.entriesJson`, decodes `ScannedEntry`, and inserts completed scan sessions named `Migrated box/session`.
6. Store a DataStore migration flag so the Kotlin legacy-pack migration is idempotent.
7. Do not drop legacy tables in this version. They remain available to backups and future recovery if a JSON row cannot be decoded.

If a legacy pack JSON row cannot decode, keep the original row untouched, log the failure, and document it in `docs/MIGRATION_NOTES.md`.

## Navigation Plan

Bottom navigation remains:

- Home
- Scan
- Current
- Collection
- Settings

Remove Pack and Box destinations from the visible nav. Legacy Pack/Box files may exist only during migration, not as routes.

## Workflow Plan

Home:

- Start new scan session.
- Continue current session.
- Pending price count.
- Collection summary.
- Last session summary.

Scan:

- Camera capture/OCR.
- Variant picker unless Rapid Mode is enabled.
- Add to active scan session only.
- No pricing call.
- Show last added, total count, pending count, and Current Session shortcut.

Current Session:

- One list/grid of all entries.
- Duplicate quantities visible.
- Pending/priced/failed/unpriceable states.
- Manual add.
- Undo last scan.
- Remove/edit/swap/change variant.
- Clear with confirmation.
- Complete/end session without requiring pricing.
- Submit pending prices with exact batch wording, e.g. `Submit 46 cards in 3 batches`.

Collection:

- Aggregate entries from all scan sessions.
- Header: total cards, unique cards, estimated value, pending prices.
- Search, sort, filter, and optional grouping.
- Sort by name, set, collector number, rarity, domain, quantity, value, recent.
- Filter by pending price, variant, set, rarity, domain, source/session.
- Submit pending prices across sessions.

## Currency Plan

Use Frankfurter as the no-key rate source:

- Endpoint: `https://api.frankfurter.dev/v2/rates?base=USD&quotes=AUD`.
- Source is public/no key per Frankfurter docs.
- Cache rate/base/target/fetchedAt/source in DataStore.
- Refresh on app launch if stale and when currency changes.
- Manual refresh from Settings.
- Never block scan, collection, or pricing if rate refresh fails.
- Prices remain stored as USD; only display formatting converts.

## Test Plan

Add unit tests for:

- Session creation, add, duplicate, remove, undo, edit, variant-change-to-pending, complete, reopen active.
- Collection aggregation, sorting, filtering, grouping, pending counts.
- Pricing batch split for 0, 1, 18, 20, 21, 46, 100.
- Cache hit exclusion from network.
- Failed batch remains retryable.
- Missing `tcgplayerId` is unpriceable.
- v3 to v4 migration creates session tables and migrates loose scans.
- Legacy pack JSON migration into completed scan sessions.
- Currency rate success/failure/stale-cache/change-currency/formatting.

Device QA stays in `docs/TEST_RUNS.md`.
