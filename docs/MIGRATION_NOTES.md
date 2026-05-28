# Migration Notes

## v3 -> v4

Purpose: replace Pack/Box user workflow with scan sessions while preserving
existing data.

Room migration:

- Creates `scan_sessions`.
- Creates `scan_session_entries`.
- Adds `cards.domains TEXT NOT NULL DEFAULT ''`.
- Copies `loose_scans` into one completed scan session:
  - session id: `legacy-loose`
  - source: `MIGRATED_LOOSE`
  - blank `tcgplayerId` rows become `UNPRICEABLE`
  - nonblank `priceJson` rows become `PRICED`
  - empty `priceJson` rows remain `PENDING`

Startup migration:

- `SessionRepository.migrateLegacyPacksIfNeeded()` decodes legacy
  `pack_sessions.entriesJson` rows.
- Each legacy box with entries becomes a completed scan session.
- Entries use source `MIGRATED_PACK`.
- A DataStore flag makes this migration idempotent.

Legacy tables are intentionally retained in v4:

- `box_sessions`
- `pack_sessions`
- `loose_scans`

They are not user-facing, but retaining them avoids destructive migration and
keeps recovery possible if a legacy JSON row cannot decode.

## Known Limitations

- Legacy pack migration is Kotlin startup work, not pure SQL, because entries
  are stored as serialized `ScannedEntry` JSON.
- Old Pack/Box feature UI files were removed. Legacy model/entities remain only
  for migration and backup/recovery.
