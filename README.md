# riftbound-pack-tally

Personal-use Android app for scanning Riftbound cards into simple scan
sessions, managing a collection, and batch-pricing cards through JustTCG.

The app is built for a Huawei P30 Pro box-day workflow: scan now, review later,
submit prices only when explicitly requested.

## Current Workflow

- **Home**: start a new scan session, continue the active session, see pending
  price count and collection summary.
- **Scan**: CameraX + ML Kit OCR identifies a card. The user picks a variant,
  or Rapid Mode records Standard immediately. Recording a scan is local only.
- **Current**: one running list for the active session. Add manually, undo,
  remove, change variant, clear, end the session, or submit pending prices.
- **Collection**: aggregates all scan-session entries. Search, sort, group,
  filter by set/rarity/domain/variant/pending state, remove entries, add
  manually, submit all pending prices, **refresh all prices** (force-refetch the
  whole collection, deduped per card), and export JSON.
- **Settings**: JustTCG key, quota/cache controls, Riftcodex sync, automatic
  exchange-rate refresh, OCR diagnostics, backups.

Pack and Box are no longer user-facing concepts. Legacy Pack/Box tables remain
in the database so existing rows can migrate into completed scan sessions.

## Pricing Rules

- JustTCG prices are stored as USD.
- Scanning never calls pricing.
- Pricing only happens from explicit Submit actions.
- Requests are batched in chunks of up to 20:
  - 18 cards = 1 POST
  - 46 cards = 3 POSTs: 20, 20, 6
  - 100 cards = 5 POSTs
- Cache hits return locally and do not burn quota.
- Cards without `tcgplayer_id` are marked unpriceable instead of crashing.
- Rate limiting against JustTCG's 10-requests-per-minute cap happens at two
  separate layers, not one shared mechanism:
  - **Ordinary Submit** (pending prices from a scan session): batched 20 per
    request. Once 7 of the 10 requests in the current minute window are used,
    each further request backs off for 6 seconds before sending. If the
    window is fully used (10/10), the remaining batch fails immediately with
    a rate-limit error rather than waiting it out. There is no countdown UI
    for this path.
  - **Refresh all prices** (Collection) force-refetches every card, bypassing
    the cache. Work is deduped to distinct `(tcgplayer_id, variant)` products
    and batched 20 per request, same as Submit, but on top of that it runs
    its own outer window capped at 10 calls: after every 10 calls it waits a
    full 60 seconds before the next window, showing a live countdown
    ("Rate limit - next in Ns") via `CollectionViewModel`/`CollectionScreen`.
    It stops early and reports if the daily/monthly quota wall or cache-only
    mode is hit, and writes each batch as it lands so partial progress is
    never lost.

## Card and Currency Data

- Card metadata syncs from Riftcodex into Room. `tcgplayer_id` is the JustTCG
  join key.
- Riftcodex `classification.domain[]` is persisted for Collection filtering.
- Currency conversion uses Frankfurter (`https://api.frankfurter.dev`) with no
  API key. USD-to-target rates are cached in DataStore and refreshed when stale
  or when the target currency changes.

## Build

```bash
./gradlew assembleDebug
./gradlew test
```

Output APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Sideload

```bash
./gradlew installDebug
adb shell am start -n com.riftbound.packtally/.MainActivity
```

On first launch, wait for Riftcodex sync, grant camera permission, and enter a
JustTCG API key in Settings before submitting prices.

## Project Layout

```text
app/src/main/java/com/riftbound/packtally/
  App.kt                     manual DI and app-wide repositories
  MainActivity.kt            first-launch sync gate and root Compose host
  core/
    carddb/                  Riftcodex sync and in-memory card lookup
    currency/                Frankfurter exchange-rate service/cache wrapper
    ocr/                     ML Kit OCR and collector-code parser
    persistence/             Room schema, scan-session repository, migration
    pricing/                 JustTCG client, cache, quota tracker
    settings/                DataStore settings
    backup/                  backup zip creation/restore preview
  feature/
    home/                    Home summary and start/continue actions
    scanner/                 Camera/OCR scan flow
    session/                 Current Session list and submit flow
    collection/              Collection aggregation/search/sort/filter/export
    settings/                Settings UI
    firstlaunch/, backup/    supporting screens
  model/                     pure domain types
  ui/                        navigation, theme, currency formatter
```

## Docs

- `docs/SCAN_SESSION_REDESIGN.md`: migration/removal plan and dependency map.
- `docs/API_NOTES.md`: Riftcodex, JustTCG, and Frankfurter details.
- `docs/MIGRATION_NOTES.md`: v4 scan-session migration.
- `docs/SMOKE_TEST.md` and `docs/QA_CHECKLIST.md`: phone QA flows.
- `docs/TEST_RUNS.md`: commands and measured results.

## Disclaimer

Personal project. Not affiliated with Riot Games, UVS Games, or TCGplayer.
Riftbound is a trademark of Riot Games.
