# Smoke Test — Box-Day Readiness

15–20 minute sequence on the P30 Pro after `adb install -r app-debug.apk`.
Each step: **action** → **expected** → *if it fails, check this*.

## Setup

1. **Uninstall + reinstall.**
   - Action: `adb uninstall com.riftbound.packtally && adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Expected: `Success` from both commands. App icon appears in launcher.
   - *Fails?* `adb devices` to confirm phone connected. USB mode = MTP. HiSuite HDB off.

2. **First launch → grant camera permission.**
   - Action: Tap app icon → Home tab loads → tap Scan tab.
   - Expected: Permission dialog. Tap Allow. Camera preview within ~2 s.
   - *Fails?* Camera permission was denied — re-trigger via Settings → Apps → Riftbound Pack Tally → Permissions.

3. **Enter tcgapi.dev API key.**
   - Action: Settings tab → API key field → paste `tcg_live_…`.
   - Expected: Field shows the key. Settings → Quota → reset counter (debug) to ensure starting clean.
   - *Fails?* Quota card missing? Check `App.onCreate` actually built `QuotaTracker`.

## Quick Scan — 3 individual cards

4. **Quick Scan a Common card (Standard).**
   - Action: Quick tab → camera opens → align bottom-left of a Common card in the guide rectangle → FAB capture → variant sheet → Standard → wait for Saved.
   - Expected: "Added to your collection" sheet with name + setCode-collectorNumber + price (e.g. A$0.16 if AUD). Session tally at top reads "1 card added · A$0.16".
   - *Fails?* OCR returning Failed? Check the OCR confidence proxy — `adb logcat -s QuickScanViewModel`. Try "Type instead" → search by name.

5. **Quick Scan a Foil card, hit "Scan Another".**
   - Action: On the Saved sheet, tap "Scan Another". Camera resumes. Capture next card. Variant sheet → Foil.
   - Expected: Saved sheet again. Session tally now "2 cards added · A$…".
   - *Fails?* Foil glare? Settings → toggle "Force OCR preprocessing" on.

6. **Quick Scan a Signature card, hit "Done".**
   - Action: Scan Another → capture signature card → Signature → Done.
   - Expected: Sheet dismisses. Camera live. Session tally persists ("3 cards added · A$…").

## Verify Collection

7. **All 3 cards appear in Collection.**
   - Action: Tap Cards (Collection) tab.
   - Expected: Total header reads 3 cards across some sets. Set-grouped list shows all 3.
   - *Fails?* Pull to refresh. Check `adb logcat -s CollectionViewModel`.

## Pack mode (partial — just to exercise the flow)

8. **Open a box (or single pack).**
   - Action: Home → toggle to "Open a Box (24 packs)" → Start scanning → land on Scan tab.
   - Expected: Camera live. Pack tab now shows pack 1, 0/14 cards.

9. **Scan 14 cards into pack 1.**
   - Action: Capture cards through the variant sheet. Use "Standard" for speed unless the card actually is foil.
   - Expected: After the 14th, the "Complete pack →" button appears in the sticky header on Pack tab.

10. **Force-stop mid-pack-2.**
    - Action: Tap Complete pack → start pack 2 → scan 5 cards. Then Phone Settings → Apps → Riftbound Pack Tally → Force stop. Reopen app.
    - Expected: Home tab shows active session. Pack tab shows pack 2 with all 5 cards intact. Box tab shows pack 1 (complete, total $X) + pack 2 (5/14 cards).
    - *Fails?* Restore was supposed to fire in `PackViewModel.init`. Check `adb logcat -s PackViewModel` for "Restore failed".

## Manual correction

11. **Swap a card in pack 1.**
    - Action: Box tab → wait, Box doesn't allow editing. Pack tab → swipe back to pack 1 view (or you may need to navigate Box → Pack — currently Pack only shows active pack, not earlier; tap any FILLED cell in the visible pack).
    - Expected: Bottom sheet opens with current card + top-3 fuzzy candidates + search field. Search "OGN" → tap a candidate → Swap → entry replaced.
    - *Note:* Pack screen only renders the active pack currently. Editing earlier-pack cards requires either drilling in from Box (not wired) or completing the active pack to push it to the active position.

## Quota awareness

12. **Settings → Quota readout.**
    - Action: Settings → scroll to Quota card.
    - Expected: `19 / 1000 requests today` (or however many you've burned). Progress bar small. "Resets in HH:MM (UTC midnight)" shows.
    - *Fails?* Quota not incrementing? Check that `QuotaAwarePricingRepository` is in the chain (App.kt).

## Export

13. **Export to JSON.**
    - Action: Cards tab → Export JSON button.
    - Expected: Toast "Exported to /storage/emulated/0/Android/data/com.riftbound.packtally/files/collection-<timestamp>.json".
    - Verify externally: `adb pull "/sdcard/Android/data/com.riftbound.packtally/files/collection-*.json" ./` → open in laptop editor → valid JSON with `sets` AND `loose_scans` arrays.

## Backup

14. **Manual backup.**
    - Action: Settings → "Backups & restore →" → "Back up now".
    - Expected: Toast with path. `/sdcard/Android/data/com.riftbound.packtally/files/backups/riftbound-backup-<ts>.zip`.
    - Verify: `adb pull` the zip → unzip on laptop → see `database.db`, `prefs.json` (no API key), `cache/...`, `manifest.json`.

## Currency switch

15. **Toggle to USD.**
    - Action: Settings → Currency segmented row → USD.
    - Expected: Conversion rate field disappears (USD = 1:1). Cards tab + Pack tab + Quick tally all re-render with `$` prices instead of `A$`.

---

**If all 15 pass:** you're ready for box day. Open the box with confidence.

**Common box-day surprises:**
- Glare from card sleeves — peel them off, or rely on the Otsu retry.
- Lighting too dim — use a desk lamp directly above the card.
- Pricing call slow — that's tcgapi.dev round-trip + cache write. Cached re-scans are instant.
- Quota at 80% surprise — the Snackbar fires once per session; after dismissing, Settings is where you watch the counter.
