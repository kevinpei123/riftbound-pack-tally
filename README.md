# riftbound-pack-tally

Personal-use Android app for scanning Riftbound TCG booster packs, identifying
each card via on-device OCR plus a local card database synced from
[Riftcodex](https://api.riftcodex.com), fetching prices in batches from
[JustTCG](https://justtcg.com), and tracking total pack and box values.

Built for one device (a Huawei P30 Pro running EMUI 10/11), one user. **Not
intended for distribution.** No Play Store listing, no signing config beyond
the local debug keystore, no Google Play Services dependency.

## What it does

- **Three scan modes**: Quick Scan (loose individual cards), Pack mode
  (one 14-card booster), Box mode (24 packs of 14 = 336 cards).
- Camera-based scanning of Riftbound cards via CameraX with a card-shaped
  (5:7) guide frame centred in a 9:16 portrait preview.
- On-device OCR via standalone ML Kit Text Recognition (no GMS required).
  The parser handles the real printed collector-code format `SET NUM/TOTAL`
  (e.g. `UNL 156/219`) plus OCR-mangled variants where the kerned space comes
  through as a period, middle-dot, bullet, or nothing at all. Alt-art letter
  suffixes (`OGN 120a/298`) and signature asterisks (`OGN 308*/298`) are
  recognised and normalised.
- Auto-identification against a ~1,000-card local catalogue synced from
  [Riftcodex](https://api.riftcodex.com) on first launch
  (~5–15 s) and refreshed manually from Settings.
- **Batched pricing**: scanning a card is priceless — the JustTCG call only
  fires when you tap **Submit**, which prices a whole pack (or every pending
  loose scan) in one batched HTTP request. Free-tier safe by design.
- Real-time market price lookup against JustTCG (free tier: 1,000/month,
  100/day, 10/min, batched ≤20) with a 6-hour file cache (configurable
  1–48 h). **QuotaTracker** persists daily counter to DataStore, fires a
  Snackbar at 80%, a confirm AlertDialog at 95%, and hard-blocks at 100%.
  Cache hits and 4xx/5xx don't count against quota.
- **Quick Scan rapid mode**: toggle to skip the variant chooser and
  auto-record every identified card as STANDARD — useful for bulk-sleeving.
- **Manual add / remove from Collection**: search by name, pick a variant,
  added as a loose scan. Per-row remove falls through to cross-pack delete
  (so a card scanned in a previously-completed pack is reachable too).
- Manual correction of OCR misreads via a bottom sheet with top-3 fuzzy
  matches, free-text search, and variant toggles. Corrections are priceless
  too — the next Submit picks them up.
- Collection aggregation across **every** scanned card (loose AND pack
  entries, regardless of pack completion) with set grouping, rarity/variant
  filters, "Manual / Quick only" filter, name search, source labels per row
  (`from pack`, `manual / quick`, `2 pack + 1 manual`), card thumbnails via
  Coil, and JSON export to external storage.
- Home screen summary card showing total cards + total value + pending price
  count at a glance.
- Multi-currency display (USD / EUR / GBP / AUD) with a manually-editable
  USD→target conversion rate.
- Room-backed session persistence so a box-in-progress survives app restarts.
- **Manual backup** to a zip in external files dir (db + sanitized prefs +
  cache + manifest). Safe to share — API key is excluded.

## Tech stack

- **Kotlin 2.2.10**, JVM target 17
- **Jetpack Compose** with Material 3 (Compose BOM 2024.12.01)
- **CameraX** 1.4.1 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **ML Kit Text Recognition** 16.0.0 — standalone variant
  (`com.google.mlkit:text-recognition`), NOT the `play-services-mlkit-*` variant
- **Retrofit** 2.11 + official `converter-kotlinx-serialization` 2.11 + **OkHttp** 4.12
- **Room** 2.7.2 (via KSP `2.2.10-2.0.2`) for session persistence
- **Coil** 2.7.0 for card thumbnails
- **Jetpack DataStore** (preferences) for settings
- **Compose Navigation** 2.8.5
- **JUnit Jupiter 5.11.3** + `junit-platform-launcher 1.11.3` for unit tests
- Pure-Kotlin **Otsu binarization** for OCR preprocessing (no OpenCV — saves
  ~30 MB of APK)
- Custom in-memory `CardDatabase` with **Levenshtein** fuzzy search backed by
  Room rows synced from Riftcodex

minSdk 28 (Android 9 — what the P30 Pro shipped with), targetSdk 34,
compileSdk 34. **Gradle 9.4.1, AGP 9.2.1.**

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Signed with the local
debug keystore.

Run unit tests:

```bash
./gradlew test
```

(34 tests at time of writing — parser regexes, backfill job, etc.)

## Sideload

1. **Phone** → Settings → About phone → tap **Build number** 7 times.
2. Settings → System & updates → Developer options → **USB debugging** on.
3. Connect USB-C, set USB mode to **File transfer (MTP)** (not HiSuite).
4. Accept the RSA fingerprint dialog the first time you connect.
5. From the project root:
   ```bash
   ./gradlew installDebug
   ```

Alternative (no laptop / adb misbehaving): copy the APK to the phone via MTP,
open it in Huawei Files, enable "Install unknown apps" for Files when prompted.

## Card data (Riftcodex)

The app pulls Riftbound's card catalogue from
[Riftcodex](https://api.riftcodex.com) on first launch and persists it to
Room. New sets appear automatically after the next sync (or tap
**Settings → Re-sync card database**).

The list endpoint envelopes cards under `items` (older builds expected `data`
or `cards`; both kept as fallbacks). Each card's `riftbound_id` looks like
`unl-060a-219` (lowercase, with optional alt-art letter suffix, set-num-total
separated by hyphens). The DTO mapping normalises this to the canonical
`SET-NUM[letter]` shape (e.g. `UNL-060a`) which is what `CardDatabase.lookupByNumber`
keys off.

Riftcodex carries the `tcgplayer_id` we need to bridge into JustTCG's pricing
API — that's the join key. Cards lacking it (rare; usually preview-only) are
dropped during sync and logged.

**Self-healing sync gate**: if the local DB ends up at 0 cards for any reason
(broken API call, schema drift, etc.), MainActivity routes back to the
FirstLaunchScreen on next launch rather than letting the user stumble into an
empty app. `CardDbSync.runFullSync()` refuses to commit an empty result and
surfaces the error on the FirstLaunchScreen with a Retry button.

If Riftcodex ever goes dark, see `docs/API_NOTES.md` → "Backup source playbook"
for the RiftScribe fallback (stubbed, ready to wire when needed).

## Getting a JustTCG API key

1. Sign up at [justtcg.com](https://justtcg.com). The free tier gives 1,000
   requests/month, 100/day, 10/min, batched ≤20 cards per request. No credit
   card required.
2. Copy your `tcg_…` key from the dashboard.
3. Launch the app → **Settings** tab → paste into the "JustTCG API key" field.

The key is stored in DataStore as plain text. Personal-use only — if this
ever becomes a multi-user app, encrypt it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| OCR misreads a card | Tap the card cell in the Pack grid → manual-correction bottom sheet. Use top-3 fuzzy candidates, free-text search, or Update Variant for foil/signature changes. Corrections null out the price; the next Submit fetches a fresh one. |
| Foil / signature cards always misread | Settings → toggle **Force OCR preprocessing** on. Even with that off, a low-confidence first pass auto-retries with grayscale + 1.5× contrast + Otsu binarization. |
| Hitting the JustTCG cap | Settings shows three buckets (monthly/daily/minute). If you're near monthly, raise the **Cache TTL** slider. If near minute, the client already auto-throttles at 7/10. Per-scan calls are gone now — only Submit fires the network. |
| Cards missing after a new Riftbound set release | Settings → **Re-sync card database (Riftcodex)** button. Pulls the latest catalogue. |
| Database stuck at 0 cards | MainActivity's `size == 0` gate sends you to FirstLaunchScreen automatically; tap Retry. If the sync keeps returning 0, the Riftcodex envelope may have changed — check `docs/API_NOTES.md`. |
| `adb` can't see the P30 Pro | Confirm USB mode is "File transfer (MTP)", not HiSuite or charging-only. Disable HiSuite HDB in Developer options. On Windows, install Huawei's USB driver. |
| Install fails with `INSTALL_FAILED_VERSION_DOWNGRADE` | `adb uninstall com.riftbound.packtally` then `adb install` again. |
| Pricing returns "Missing JustTCG API key" | Settings → API key field is empty. Paste the key. |
| Box-in-progress disappeared after force-stop | Open the **Home** tab — the most recent unfinished session should be restored from Room. If it doesn't appear, check `adb logcat` for `SessionRepository` errors. |
| Camera goes black after switching from Scanner ↔ Quick Scan | Fixed in this build — each `CameraScreen` instance now tracks the `Preview` + `ImageCapture` use-cases it bound and only unbinds those on dispose. If it recurs, file a regression. |

## Project layout

```
app/src/main/java/com/riftbound/packtally/
├── App.kt                       # Application class, wires CardDatabase, pricing pipeline, Room, settings
├── MainActivity.kt              # Hosts AppNav, FirstLaunchScreen gate (cards.size > 0), LocalCurrencyFormatter
├── core/
│   ├── carddb/                  # CardDatabase, CardDbSync, RiftcodexClient, RiftscribeClient (stub)
│   ├── ocr/                     # OcrService, CardOcrParser (handles SET NUM/TOTAL with OCR variants)
│   ├── pricing/                 # PricingRepository interface + JustTcg/Cached/Mock impls + QuotaTracker
│   ├── persistence/             # Room entities, DAOs (Session + LooseScan + Card), BackfillJob
│   ├── settings/                # SettingsRepository, DataStore impl, AppSettings, Currency
│   └── backup/                  # BackupRepository (manual zip backup + restore)
├── feature/
│   ├── home/                    # HomeScreen + mode toggle + collection summary card
│   ├── firstlaunch/             # FirstLaunchScreen (Riftcodex sync gate)
│   ├── quickscan/               # QuickScanScreen, QuickScanViewModel (rapid mode, submit-all)
│   ├── scanner/                 # CameraScreen, ScannerScreen, ScannerViewModel (priceless append)
│   ├── pack/                    # PackScreen (14-cell grid), PackViewModel (submitPack), CorrectionSheet
│   ├── box/                     # BoxScreen
│   ├── collection/              # CollectionScreen, CollectionViewModel (manual add/remove, search, submit)
│   ├── backup/                  # BackupScreen
│   └── settings/                # SettingsScreen, SettingsViewModel
├── model/                       # RiftboundCard, Rarity, ScannedEntry (nullable price), PackSession, BoxSession
└── ui/
    ├── currency/CurrencyFormatter.kt
    ├── nav/                     # Destination enum (7 tabs), AppNav
    └── theme/                   # Material 3 theme
```

## First-time setup on a fresh phone

```bash
# On the phone: enable Developer Options + USB debugging.
# On laptop:
./gradlew clean installDebug
adb shell am start -n com.riftbound.packtally/.MainActivity
```

Inside the app: wait for FirstLaunchScreen to finish (~5–15 s sync of ~1,000
cards). Grant camera permission, Settings → paste your JustTCG API key.
Optionally toggle Force OCR preprocessing if your test cards are foils.
Run through `docs/SMOKE_TEST.md` to verify.

## Daily usage / box-day workflow

1. **Before opening a box:** Settings → Backups → Back up now. Pulls a fresh
   zip you can keep on USB.
2. **Open the box:** Home → "Open a Box (24 packs)" → Start scanning.
3. **Scan each pack:** 14 cards per pack. Each scan adds the card to the grid
   with no price. After the 14th card, the header button reads
   **"Submit & complete pack →"** (or "Submit & finish" on the last pack).
   Tapping it batches all 14 prices into one JustTCG call, patches them into
   the grid, then advances to the next pack.
4. **Mid-pack submit:** Need to see prices halfway through? The button
   becomes **"Submit N cards for pricing"** whenever there are unpriced
   entries; tapping it prices without advancing.
5. **Mid-box pause:** safe — every scan persists immediately. Force-stop the
   app, come back later, Pack tab restores. Prices for any submitted packs
   are persisted; unpriced cards stay unpriced.
6. **Quota check:** Settings → Quota card shows requests used today.
7. **After the box:** Cards tab → Export JSON → `adb pull` for your records.

See `docs/ARCHITECTURE.md` for the scan-to-record data flow,
`docs/QA_CHECKLIST.md` for a pre-box checklist, `docs/SMOKE_TEST.md` for the
post-install sanity sequence, and `docs/TROUBLESHOOTING.md` for symptom→fix
mapping.

## Quick Scan vs Pack mode vs Box mode

| Mode | Use when | Where data goes | Submit |
|---|---|---|---|
| Quick Scan | Loose cards a friend handed you. Bulk-sleeving with Rapid Mode on. Testing OCR with a known card. | `loose_scans` table (price=null until submitted) | "Submit N cards for pricing" chip at the top of the camera screen |
| Pack mode | Just opened one booster. Want to track exactly 14 cards. | Active `PackSession` in Room | Header button at the top of the grid |
| Box mode | Opening a full 24-pack box. Want pack-by-pack subtotals + a grand total. | `BoxSession` with up to 24 `PackSession`s. Auto-rollover on the 15th scan. | Same header button — submits the active pack, then advances |

You can also submit pending loose scans from **Collection** (the "Submit N pending"
button appears in the Collection header), so you don't have to bounce back to
Quick Scan after a manual add.

## Known limitations / deferred work

- **Restore from backup file** isn't fully wired — Settings shows the path,
  but actual swap-the-db-and-reopen logic isn't implemented.
- **Auto-backup via WorkManager** is scaffolded but the periodic job isn't
  registered.
- **Alternative pricing providers** (TCGCSV, Cardmarket) — not wired.
- **Custom price override** per card — not wired.
- **Unit tests for `QuotaTracker`** with a real DataStore require either
  Robolectric or `datastore-preferences-core` in `testImplementation`.
- **`docs/PERFORMANCE.md`** has TARGETS but no measured numbers — run them on
  the device.

## Disclaimer

Personal project. Not affiliated with Riot Games, UVS Games, or TCGplayer.
Riftbound is a trademark of Riot Games.
