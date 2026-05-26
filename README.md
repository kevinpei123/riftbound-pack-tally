# riftbound-pack-tally

Personal-use Android app for scanning Riftbound TCG booster packs, identifying
each card via on-device OCR plus a local card database, fetching prices from
[tcgapi.dev](https://tcgapi.dev), and tracking total pack and box values.

Built for one device (a Huawei P30 Pro running EMUI 10/11), one user. **Not
intended for distribution.** No Play Store listing, no signing config beyond
the local debug keystore, no Google Play Services dependency.

## What it does

- Camera-based scanning of Riftbound cards via CameraX with a guide rectangle
  aimed at the bottom-left collector number.
- On-device OCR via standalone ML Kit Text Recognition (no GMS required).
- Auto-identification against a 950+ card local database scraped from the
  official Riftbound gallery.
- Real-time market price lookup against tcgapi.dev with a 6-hour file cache
  (configurable 1–24 h) to stay well under the free tier's 100 req/day cap.
- Pack mode (14 cards) and Box mode (24 packs × 14 cards), with auto-rollover
  when a pack fills.
- Manual correction of OCR misreads via a bottom sheet with the top-3 fuzzy
  matches, free-text search, and variant toggles.
- Collection aggregation across all completed packs with set grouping,
  rarity/variant filters, and JSON export to external storage for USB grab.
- Multi-currency display (USD / EUR / GBP / AUD) with a manually-editable
  USD→target conversion rate.
- Room-backed session persistence so a box-in-progress survives app restarts.

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

## Disclaimer

Personal project. Not affiliated with Riot Games, UVS Games, or TCGplayer.
Riftbound is a trademark of Riot Games.
