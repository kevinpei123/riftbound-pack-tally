# QA Checklist — Box-Day Readiness

Run through this list on the P30 Pro before opening a real 336-card box. Each
item: action + expected + failure tip. Estimated 30 minutes if everything is
green.

## Install + first-launch sync

1. **Fresh install** — `adb uninstall com.riftbound.packtally` then
   `./gradlew installDebug`. App icon appears, first launch goes to
   FirstLaunchScreen.
2. **Riftcodex sync** — FirstLaunchScreen shows "Setting up card database".
   Expected: ~5–15 s, then routes to AppNav with the Home tab showing
   "Your collection · 0 cards". `adb logcat -s CardDbSync` should print
   `Sync complete — 1064 cards in DB` (or whatever Riftcodex has now).
3. **Sync-gate self-heal** — Manually corrupt by `adb shell sqlite3` deleting
   all rows in `cards` table (or just `Settings → Reset all data`). Re-launch.
   Expected: FirstLaunchScreen reappears and re-syncs.

## Permissions + camera

4. **Camera permission grant** — Home → Scan tab → permission dialog → Allow.
   Camera preview appears within ~2 s.
5. **Camera permission deny** — Same flow, tap Deny. Screen shows
   "Grant permission" + "Open app settings". Tapping Grant re-shows the dialog.
6. **Camera tab switch (was a regression in earlier builds)** — Tap Scan tab,
   then Quick Scan tab, then back to Scan tab, rapidly. Both should show a
   live preview every time. Expected: no black screen, no crash, no
   "Camera could not be bound" log line.

## Scanning under various conditions

7. **Bright outdoor lighting** — Scan one common card. Expected: identified
   with ≥0.9 confidence within ~4 s. Check the bottom-left of the card sits
   inside the 5:7 frame.
8. **Low-light scan** — Indoor desk lamp only. Expected: identification still
   works but with lower confidence. After 3 failures, "OCR struggling? Type
   the card name instead →" appears at the top of Quick Scan.
9. **Foil card scan** — Expected: OCR may need 2 passes (raw + Otsu) —
   automatic. Result still resolves. If chronically failing, toggle "Force
   OCR preprocessing" in Settings.
10. **OCR variant — period separator** — Scan a card whose collector code OCRs
    as `UNL . 156/219` (instead of `UNL 156/219`). Expected: still identified.
    The parser absorbs space/period/middle-dot/bullet between SET and NUM.
11. **Alt-art / signature card** — Scan a card with `OGN 120a/298` (alt-art
    suffix) or `OGN 308*/298` (signature asterisk). Expected: identified with
    the base printing as the lookup target.
12. **Two identical cards in the same pack** — Scan the same card twice in
    Pack mode. Both appear as separate entries in the 14-cell grid.

## Session persistence

13. **Mid-pack force-stop → resume** — Scan 7 cards in pack 1, force-stop,
    re-launch. Home shows "Active: …", Pack tab shows pack 1 with all 7 cards
    intact, each showing `—` for price (not submitted yet).
14. **Mid-box force-stop → resume** — Complete pack 1 (submit it),
    start pack 2 with 3 cards. Force-stop, reopen. Pack 1 prices intact, pack
    2 shows 3 unpriced cards.

## Batched pricing (THE primary pricing path now)

15. **Submit a pack** — Pack tab → fill 14 cards → header button reads
    "Submit & complete pack →". Tap. Expected: progress spinner appears,
    one HTTP POST in `adb logcat -s okhttp.OkHttpClient`, all 14 cells get
    prices, header total updates, toast shows "Priced 14 cards — $X.XX",
    pack advances to pack 2.
16. **Mid-pack submit** — Fill 8 cards. Button reads "Submit 8 cards for
    pricing". Tap. Expected: 8 prices populate; pack stays at pack 1 with 8/14
    filled. Continue scanning into the same pack.
17. **Submit failure path** — Airplane mode on, then Submit. Expected: toast
    "Pricing failed — …", entries stay unpriced, pack does NOT advance.
    Airplane mode off → Submit again, prices populate.
18. **Submit on Collection** — Add 3 cards via Collection → "+ Add card".
    Tab to Home — summary card shows "3 pending price". Tab to Collection —
    header now shows "Submit 3 cards for pricing" button. Tap. Toast confirms.
19. **Submit on Quick Scan** — Quick-scan 5 cards. Top chip shows pending
    count. "Submit 5 cards for pricing" appears below the tally. Tap. Toast.

## Quick Scan flow

20. **Quick Scan → Saved → Scan Another** — Capture → variant → "Added to
    your collection · price pending" sheet → Scan Another → camera resumes.
21. **Quick Scan Rapid Mode** — Toggle Rapid Mode at the top. Scan a card.
    Expected: variant sheet does NOT appear; the card is saved as STANDARD
    immediately and camera goes straight back to live preview. Top chip
    sub-line shows "Last: <card name>". Increments pending count.
22. **Loose scan appears in Collection** — Switch to Collection tab. The
    cards just scanned should appear in their set groups with source label
    "manual / quick" and "· price pending" until Submit fires.

## Manual add / remove from Collection

23. **Manual add** — Collection → "+ Add card" → search by name → pick a card
    → pick variant. Expected: sheet closes, toast "Added <name>", row appears
    in Collection with the source label "manual / quick" and `—` price.
24. **Manual remove (loose source)** — On a row added manually, tap the "−"
    icon at the end → confirm dialog → tap Remove. Expected: row decrements
    by 1 (or disappears if qty was 1), toast "Removed one <name>".
25. **Cross-pack remove (the Loyal Poro fix)** — Identify a card that's
    inside a previously-completed pack (visible in Collection with source
    label "from pack"). Tap "−" → Remove. Expected: row decrements, toast
    success, the underlying pack's entry count drops. Tab to Pack — if that
    completed box becomes the most recent unfinished box, Pack tab now shows
    it with one fewer card; otherwise no visible change in Pack tab (the
    edit landed on a non-active box).
26. **Search in Collection** — Type partial name. Rows filter live. Clear
    the field. Filter shows everything again.
27. **"Manual / Quick only" chip** — Tap to enable. Expected: only entries
    whose `looseQuantity > 0` show. Pack-only rows hide.

## Manual correction (Pack tab)

28. **Swap a card in a pack** — Pack tab → tap any filled cell → bottom
    sheet → search → pick a different card → Swap. Expected: entry replaced
    with `—` price (since corrections are priceless). The next Submit
    re-prices it.
29. **Update variant** — Same sheet, toggle Foil → "Variant". Expected:
    entry's variant changes, price nulled, next Submit re-prices.
30. **Delete entry** — Same sheet, Delete. Cell becomes "Tap to scan".

## Pricing + cache

31. **Cache hit doesn't burn quota** — Submit a pack. Note quota counter in
    Settings. Re-submit (corrections + re-submit, or remove+re-add+submit).
    Quota stays the same for already-cached entries.
32. **Quota counter increments only on network calls** — Submit a pack →
    counter goes up by 1, not 14.

## Export + backup

33. **Export to JSON** — Cards tab → Export. Toast shows path.
    `adb pull "/sdcard/Android/data/com.riftbound.packtally/files/collection-*.json"`
    → valid JSON with `sets` AND `loose_scans` arrays.
34. **Manual backup** — Settings → Backups → Back up now. Toast with path.
    Unzip the file → `database.db`, `prefs.json` (no API key), `cache/`,
    `manifest.json`.

## UI polish

35. **USD ↔ AUD currency switch** — Settings → Currency segmented row.
    Toggle. Every screen with prices re-renders.
36. **Dark mode** — Phone Settings → Display → Dark mode. App switches.
37. **Bottom nav fits on one line** — Verify all 7 labels
    (Home / Quick / Scan / Pack / Box / Cards / Set) are on one line at
    a normal text-scale setting.
38. **Card thumbnails load** — Collection entries with `imageUrl` show a
    32×44 dp clipped thumbnail. Cards without one show a neutral
    placeholder rectangle.

## Edge cases

39. **Back button mid-pack** — Pack tab with cards filled → press back.
    No confirmation dialog, session safely persisted in Room.
40. **50-card session — no UI jank** — Quick-scan 50 cards in Rapid Mode.
    Scrolling Collection stays smooth.

---

**Cold start sanity** (separate from this 40): cold-start to first usable
screen should be under 2 s. Measure with
`adb shell am start -W -n com.riftbound.packtally/.MainActivity` and read the
`TotalTime` field. Document the actual number in `docs/PERFORMANCE.md`.
