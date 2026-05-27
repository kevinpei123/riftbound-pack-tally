# Smoke Test — Post-Install Sanity

15–20 minute sequence on the P30 Pro after `./gradlew installDebug`. Each
step: **action** → **expected** → *if it fails, check this*.

## Setup

1. **Reinstall fresh.**
   - Action: `adb uninstall com.riftbound.packtally && ./gradlew installDebug`
   - Expected: `Success` from both. App icon appears in launcher.
   - *Fails?* `adb devices` to confirm phone connected. USB mode = MTP.

2. **First launch → Riftcodex sync.**
   - Action: Tap app icon. App opens to FirstLaunchScreen.
   - Expected: "Setting up card database" + spinner. After ~5–15 s,
     transitions to AppNav. `adb logcat -s CardDbSync` shows
     `Sync complete — 1064 cards in DB` (or current catalogue size).
   - *Fails?* Network down? Riftcodex envelope change? See `docs/API_NOTES.md`.

3. **Camera permission.**
   - Action: Tap Scan tab. Dialog. Allow.
   - Expected: Camera preview in the 5:7 frame within ~2 s.

4. **Enter JustTCG API key.**
   - Action: Settings tab → API key field → paste `tcg_…`.
   - Expected: Field shows the key. No error message under the field.

## Quick Scan — priceless

5. **Quick Scan a Standard card.**
   - Action: Quick tab → camera live → align card in frame → tap FAB →
     variant sheet → Standard.
   - Expected: "Added to your collection · price pending" sheet. Top chip
     shows "1 card added · 1 pending price".
   - *Fails?* OCR returning Failed? `adb logcat -s QuickScanViewModel`.
     Use "Type instead" to bypass.

6. **Rapid Mode.**
   - Action: Toggle "Rapid mode" at the top. Scan another card.
   - Expected: No variant sheet. Card saved as STANDARD. Top chip sub-line
     reads "Last: <card name>". Tally now "2 cards added · 2 pending price".

7. **Submit all pending from Quick Scan.**
   - Action: Tap "Submit 2 cards for pricing".
   - Expected: Spinner. One POST in
     `adb logcat -s okhttp.OkHttpClient` → 200 response. Toast
     "Priced 2 cards — $X.XX". Pending count drops to 0; tally total updates.

## Collection — manual add + remove + submit

8. **Verify Quick Scan cards in Collection.**
   - Action: Tap Cards tab.
   - Expected: Both cards listed with thumbnails and source label
     "manual / quick".

9. **Manual add a known card.**
   - Action: "+ Add card" → search "Vilemaw" → tap a result → pick Standard.
   - Expected: Sheet closes. Toast "Added Vilemaw". Row appears in Collection
     with thumbnail and `—` for total value.

10. **Header shows submit button for pending.**
    - Expected: "Submit 1 card for pricing" appears between
      Add card / Export and the search bar.

11. **Submit from Collection.**
    - Action: Tap the Submit button.
    - Expected: Spinner → toast confirming → price populates.

12. **Manual remove.**
    - Action: Tap the "−" icon on the Vilemaw row → confirm.
    - Expected: Row removed (or quantity decremented). Toast "Removed one
      Vilemaw".

## Pack mode — batched submit

13. **Start a single pack.**
    - Action: Home → "Single Pack" → Start scanning. Land on Scan tab.
    - Expected: Camera live. Pack tab shows 0/14 cards.

14. **Scan 14 cards.**
    - Action: Capture 14 cards through the variant sheet.
    - Expected: Grid fills up. Running total stays $0.00 (priceless until
      Submit). Header button changes from
      "Submit N cards for pricing" → "Submit & finish" when the pack fills.

15. **Submit the pack.**
    - Action: Tap "Submit & finish".
    - Expected: Spinner. One POST. Prices populate in every cell. Running
      total updates. Toast "Priced 14 cards — $X.XX".

## Cross-pack remove

16. **Verify pack entries in Collection.**
    - Action: Cards tab.
    - Expected: 14 new rows with source label "from pack".

17. **Remove one cross-pack.**
    - Action: Tap "−" on any pack-derived row → Remove.
    - Expected: Row decrements, toast success.
    - *Verify:* `adb shell sqlite3 ...` the `pack_sessions.entriesJson`
      column has one fewer entry, OR navigate back to Pack tab — if the
      previously-completed pack becomes the active box, the missing card
      shows as a "Tap to scan" cell.

## Camera tab switching

18. **Rapid Scanner ↔ Quick Scan switch.**
    - Action: Tap Scan tab → Quick Scan tab → Scan tab → Quick Scan tab in
      quick succession.
    - Expected: Live camera preview every time. No black screen, no crash.
    - *Fails?* Check `adb logcat` for `CameraScreen` errors. The fix tracks
      owned use-cases per screen; if both screens are unbinding all,
      something regressed.

## Persistence

19. **Force-stop → reopen mid-pack.**
    - Action: Mid-pack, force-stop via Phone Settings. Reopen.
    - Expected: Home shows active session. Pack tab restores all scanned
      cards exactly as left, with their priced/unpriced state intact.

## Export

20. **Export to JSON.**
    - Action: Cards tab → Export JSON.
    - Expected: Toast "Exported to /storage/…/collection-<ts>.json".
      `adb pull` confirms valid JSON with `sets` and `loose_scans` arrays.

---

**If all 20 pass:** you're ready for box day.

**Common surprises:**
- First launch is slow because of the Riftcodex sync. Subsequent launches
  are instant.
- Submit failure = network blip. Hit Submit again, entries stay in place.
- Scanning a card that isn't in the local DB shows a Failed sheet. Either
  re-sync from Settings, or manually pick from search.
