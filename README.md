# riftbound-pack-tally

Personal-use Android app for scanning Riftbound TCG booster packs, identifying
each card via on-device OCR plus a local card database, fetching prices from
[tcgapi.dev](https://tcgapi.dev), and tracking total pack and box values.

Built for one device (a Huawei P30 Pro running EMUI 10/11), one user. **Not
intended for distribution.** No Play Store listing, no signing config beyond
the local debug keystore, no Google Play Services dependency.

## What it does

- **Three scan modes**: Quick Scan (loose individual cards), Pack mode
  (one 14-card booster), Box mode (24 packs of 14 = 336 cards).
- Camera-based scanning of Riftbound cards via CameraX with a guide rectangle
  aimed at the bottom-left collector number.
- On-device OCR via standalone ML Kit Text Recognition (no GMS required).
- Auto-identification against a 950+ card local database scraped from the
  official Riftbound gallery.
- Real-time market price lookup against tcgapi.dev with a 24-hour file cache
  (configurable 1–48 h). **QuotaTracker** persists daily counter to DataStore,
  fires a Snackbar at 80%, a confirm AlertDialog at 95%, and hard-blocks at
  100%. Cache hits and 4xx/5xx don't count against quota, matching tcgapi.dev's
  Hobby tier billing (1000 req/day).
- Pack mode (14 cards) and Box mode (24 packs × 14 cards), with auto-rollover
  when a pack fills.
- Manual correction of OCR misreads via a bottom sheet with the top-3 fuzzy
  matches, free-text search, and variant toggles.
- Collection aggregation across all completed packs with set grouping,
  rarity/variant filters, and JSON export to external storage for USB grab.
- Multi-currency display (USD / EUR / GBP / AUD) with a manually-editable
  USD→target conversion rate.
- Room-backed session persistence so a box-in-progress survives app restarts.
- **Manual backup** to a zip in external files dir (db + sanitized prefs +
  cache + manifest). Safe to share — API key is excluded.

## Tech stack

- **Kotlin 2.0.21**, JVM target 17
- **Jetpack Compose** with Material 3 (Compose BOM 2024.12.01)
- **CameraX** 1.4.1 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **ML Kit Text Recognition** 16.0.0 — standalone variant
  (`com.google.mlkit:text-recognition`), NOT the `play-services-mlkit-*` variant
- **Retrofit** 2.11 + **OkHttp** 4.12 + **kotlinx.serialization** for tcgapi.dev
- **Room** 2.6.1 (via KSP) for session persistence
- **Jetpack DataStore** (preferences) for settings
- **Compose Navigation** 2.8.5
- **JUnit 5** for unit tests
- Pure-Kotlin **Otsu binarization** for OCR preprocessing (no OpenCV — saves
  ~30 MB of APK)
- Custom in-memory `CardDatabase` with **Levenshtein** fuzzy search

minSdk 28 (Android 9 — what the P30 Pro shipped with), targetSdk 34,
compileSdk 34. Gradle 8.10.2, AGP 8.7.3.

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Signed with the local
debug keystore.

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

## Sideload

Quick version (full walkthrough lives in conversation notes):

1. **Phone** → Settings → About phone → tap **Build number** 7 times.
2. Settings → System & updates → Developer options → **USB debugging** on.
3. Connect USB-C, set USB mode to **File transfer (MTP)** (not HiSuite).
4. Accept the RSA fingerprint dialog the first time you connect.
5. From the project root:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Alternative (no laptop / adb misbehaving): copy the APK to the phone via MTP,
open it in Huawei Files, enable "Install unknown apps" for Files when prompted.

## Generating the local card database

`app/src/main/assets/cards.json` is what `CardDatabase` loads at app start. To
rebuild it (e.g., after a new Riftbound set releases):

```bash
pip install requests beautifulsoup4
python3 scripts/build_cards_json.py
```

The script scrapes the `__NEXT_DATA__` JSON blob out of the official Riftbound
gallery page, normalizes the 950+ card records into the schema
`CardDatabase` expects, and writes the file. Re-runs are idempotent and
overwrite the existing file. Any cards that fail to parse get logged to
`scripts/build_cards_json.failures.log`.

The script defaults `isFoilByDefault` and `hasSignatureVariant` to `false`
on every card because the official site doesn't expose those flags. You can
hand-tune those columns in the JSON if you want — the OGS set's 24 cards
are signature variants, and the `showcase` rarity (181 cards) is the
alternate-art treatment you'd likely flag as foil-default.

## Getting a tcgapi.dev API key

1. Sign up at [tcgapi.dev](https://tcgapi.dev). Free tier gives 100 req/day
   with no credit card.
2. Copy your `tcg_live_...` key from the dashboard.
3. Launch the app → **Settings** tab → paste into the
   "tcgapi.dev API key" field.

The key is stored in DataStore as plain text. Personal-use only — if this
ever becomes a multi-user app, encrypt it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| OCR misreads a card | Tap the card cell in the Pack grid → manual-correction bottom sheet. Use top-3 fuzzy candidates, free-text search, or Update Variant for foil/signature changes. |
| Foil / signature cards always misread | Settings → toggle **Force OCR preprocessing** on. Even with that off, a low-confidence first pass auto-retries with grayscale + 1.5× contrast + Otsu binarization. |
| Hitting the 100/day tcgapi.dev cap | Settings → bump the **Cache TTL** slider higher (up to 24 h). Same `(card, foil, signature)` tuple within the window won't refetch. If you're still hitting it, upgrade to tcgapi.dev's Hobby tier. |
| `adb` can't see the P30 Pro | Confirm USB mode is "File transfer (MTP)", not HiSuite or charging-only. Disable HiSuite HDB in Developer options. On Windows, install Huawei's USB driver. On Linux, add a udev rule for vendor `12d1`. |
| Install fails with `INSTALL_FAILED_VERSION_DOWNGRADE` | `adb uninstall com.riftbound.packtally` then `adb install` again — the debug APK probably has a lower `versionCode` than what's already on the device. |
| Pricing returns "Missing tcgapi.dev API key" | Settings → API key field is empty or whitespace. Paste the key and tap outside the field to commit. |
| Box-in-progress disappeared after force-stop | Open the **Home** tab — the most recent unfinished session should be restored from Room. If it doesn't appear, Room may have failed to read; check `adb logcat` for `SessionRepository` errors. |
| Bottom nav looks cramped (6 tabs) | Expected — Home/Scan/Pack/Box/Collection/Settings. Material 3 recommends ≤ 5; the natural consolidation if it ever bothers you is collapsing Pack+Box into one tab that switches view based on `BoxSession.Mode`. |

## Project layout

```
app/src/main/java/com/riftbound/packtally/
├── App.kt                       # Application class, wires CardDatabase, pricing pipeline, Room, settings
├── MainActivity.kt              # Hosts AppNav, provides LocalCurrencyFormatter
├── core/
│   ├── carddb/CardDatabase.kt   # In-memory card lookup + Levenshtein fuzzy search
│   ├── ocr/                     # OcrService, CardOcrParser
│   ├── pricing/                 # PricingRepository interface + Http/Mock/Cached impls
│   ├── persistence/             # Room entities, DAO, SessionDatabase, SessionRepository
│   └── settings/                # SettingsRepository, DataStore impl, AppSettings, Currency
├── feature/
│   ├── home/                    # HomeScreen + mode toggle
│   ├── scanner/                 # CameraScreen, ScannerScreen, ScannerViewModel, ScanResult
│   ├── pack/                    # PackScreen (14-cell grid), PackViewModel, CorrectionSheet
│   ├── box/                     # BoxScreen
│   ├── collection/              # CollectionScreen, CollectionViewModel
│   └── settings/                # SettingsScreen, SettingsViewModel
├── model/                       # RiftboundCard, Rarity, ScannedEntry, PackSession, BoxSession
└── ui/
    ├── currency/CurrencyFormatter.kt
    ├── nav/                     # Destination enum, AppNav
    └── theme/                   # Material 3 theme
scripts/
└── build_cards_json.py          # Riftbound gallery scraper
```

## First-time setup on a fresh phone

```bash
# On the phone: enable Developer Options + USB debugging (see prompt-19 walkthrough).

# On laptop:
./gradlew clean assembleDebug
adb devices                                                # confirm P30 Pro detected
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.riftbound.packtally/.MainActivity
```

Inside the app: grant camera permission, Settings → paste your tcgapi.dev
API key. Optionally toggle Force OCR preprocessing if your test cards are
foils. Run through `docs/SMOKE_TEST.md` to verify.

## Daily usage / box-day workflow

1. **Before opening a box:** Settings → Backups → Back up now. Pulls a fresh
   zip you can keep on USB.
2. **Open the box:** Home → "Open a Box (24 packs)" → Start scanning.
3. **Scan each pack:** 14 cards per pack. After the 14th, "Complete pack →"
   appears in the sticky header.
4. **Mid-box pause:** safe — every scan persists immediately. Force-stop the
   app, come back later, Pack tab restores.
5. **Quota check:** Settings → Quota card shows requests used today.
   Reset counter only if you've genuinely wrapped to a new tier.
6. **After the box:** Cards tab → Export JSON → `adb pull` for your records.

See `docs/ARCHITECTURE.md` for the scan-to-record data flow, `docs/QA_CHECKLIST.md`
for a pre-box checklist, `docs/SMOKE_TEST.md` for a 15-step post-install
sanity sequence, and `docs/TROUBLESHOOTING.md` for symptom→fix mapping.

## Quick Scan vs Pack mode vs Box mode

| Mode | Use when | Where data goes |
|---|---|---|
| Quick Scan | Friend hands you a card. Single-card valuation. Testing OCR with a known card. | `loose_scans` table. Appears in Collection but separate from packs/boxes. |
| Pack mode | Just opened one booster. Want to track exactly 14 cards. | Active `PackSession` in Room. |
| Box mode | Opening a full 24-pack box. Want pack-by-pack subtotals + a grand total. | `BoxSession` with up to 24 `PackSession`s. Auto-rollover on the 15th scan. |

## Known limitations / deferred work

- **Restore from backup file** isn't fully wired — Settings shows the path,
  but actual swap-the-db-and-reopen logic isn't implemented. Use `adb push`
  + manual extract for now (see `docs/TROUBLESHOOTING.md`).
- **Auto-backup via WorkManager** is scaffolded but the periodic job isn't
  registered.
- **`LooseScansScreen`** (list view of just loose scans with swipe-to-delete)
  is mentioned in Phase 3 but defers to the Collection tab's grouped view.
- **Source-breakdown chart** (pack vs loose) on Collection deferred.
- **"Type instead" button on ScannerScreen (pack mode)** deferred — Quick Scan
  has it after 3 OCR failures.
- **Unit tests for `QuotaTracker`** with a real DataStore require either
  Robolectric or `datastore-preferences-core` in `testImplementation`.
- **`docs/PERFORMANCE.md`** has TARGETS but no measured numbers — run them on
  the device.

## Disclaimer

Personal project. Not affiliated with Riot Games, UVS Games, or TCGplayer.
Riftbound is a trademark of Riot Games.
