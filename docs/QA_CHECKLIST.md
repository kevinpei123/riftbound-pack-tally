# QA Checklist — Box-Day Readiness

Run through this list on the P30 Pro before opening a real 336-card box. Each
item: action + expected + failure tip. Estimated 30 minutes if everything is
green.

## Install + permission flow

1. **Fresh install** — `adb uninstall com.riftbound.packtally` then `adb install -r app-debug.apk`. App icon appears, first launch shows Home tab.
2. **Camera permission grant** — Home → Scan tab → permission dialog → Allow. Camera preview appears within ~2s.
3. **Camera permission deny** — Same flow, tap Deny. Screen shows "Grant permission" + "Open app settings" buttons. Tapping Grant re-shows the dialog.
4. **Camera permission revoked from system settings while app running** — Phone Settings → Apps → Riftbound Pack Tally → Permissions → Camera → Deny while app is open. Switch back to app. Scan tab should re-show the "Grant permission" state cleanly (not a crash).

## Scanning under various conditions

5. **Bright outdoor lighting** — Scan one common card outdoors. Expected: identified with ≥0.9 confidence within ~4s. If consistently failing, check the guide rectangle aim.
6. **Low-light scan** — Indoor desk lamp only. Expected: identification still works but with lower confidence. If multiple failures in a row, "OCR struggling? Type the card name instead →" appears at the top.
7. **Foil card scan** — Scan a foil card. Expected: OCR may need 2 passes (raw + Otsu) — automatic. Result still resolves. If chronically failing, toggle "Force OCR preprocessing" in Settings.
8. **Signature card scan** — Same as foil but for a signature card. Watch confidence — may be lower due to extra ink.
9. **Two identical cards in the same pack** — Scan the same card twice in a row in Pack mode. Both should appear as separate entries in the 14-cell grid.

## Session persistence

10. **Mid-pack force-stop → resume** — Scan 7 cards in pack 1. System Settings → Apps → Force stop. Re-launch app. Expected: Home tab shows "Active: …", Pack tab shows pack 1 with all 7 cards.
11. **Mid-box force-stop → resume** — Complete pack 1, start pack 2 with 3 cards in it. Force stop, reopen. Expected: Box tab shows pack 1 (14 cards, $X) + pack 2 (3 cards, $Y). Pack tab shows pack 2 (the active partial).

## Network / quota

12. **Airplane mode → scan** — Airplane mode on. Scan a card. Expected: identification works (CardDatabase is local); pricing returns "Network unavailable" (or similar) and the entry is NOT added to the pack (failure path). Switch back online → re-scan succeeds.
13. **80% quota Snackbar** — Set quota to ~800/1000 via the Settings → Quota → "Reset counter (debug)" button (well, you'd reset to 0; to test the threshold you'd need 800 real network calls or temporarily patch the threshold). Practical alternative: trust the unit-test coverage and verify on a real box day.
14. **95% quota dialog** — Same — only triggers near actual exhaustion. Verify the dialog body, the three actions ([Use cached only] [Continue] [Cancel]), and that "Use cached only" actually blocks subsequent network calls.
15. **Manual entry fallback after 3 OCR failures** — Cover the lens with your thumb and tap capture 3 times. After the 3rd failed identify, "Type the card name instead →" should appear at the top. Tap → manual entry sheet → search → pick → variant picker → save.

## Quick Scan flow

16. **Quick Scan → "Scan Another" rapid-fire** — QuickScan tab → scan card 1 → Foil → Scan Another → card 2 → Standard → Scan Another → card 3 → Signature → Done. Session tally chip at top should read "3 cards added · $X.XX total".
17. **Quick Scan → "Done"** — On the Saved sheet, tap Done. Sheet dismisses, returns to live camera. Session tally still visible until you leave the tab.
18. **Loose scan appears in Collection** — Switch to Collection tab. Cards just scanned in Quick Scan should appear in their respective set groups.

## Manual correction

19. **Correct a pack card via swap** — Pack tab → tap any filled cell → bottom sheet appears → search "Brazen" → pick a different card → "Swap" → original entry replaced, total updates.
20. **Correct a pack card via variant update** — Same flow, but toggle Foil → "Variant" → entry's variant + price update without changing the card.
21. **Delete a pack entry** — Same flow, tap "Delete". Entry removed, grid cell becomes empty/Tap-to-scan.

## Pricing + cache

22. **Cache hit doesn't burn quota** — Scan a card. Check Settings → Quota → count = X. Re-scan the SAME card (same variant). Quota should still be X (cache hit). Check the timestamp on the cached value via `adb shell ls -la /data/data/com.riftbound.packtally/cache/prices/`.
23. **Pull-to-refresh respects quota** — Collection tab → swipe down to refresh. Entries newer than cache TTL stay; older ones may refetch. Quota increments accordingly.

## Export + backup

24. **Export to JSON** — Collection tab → Export. Toast shows the path. `adb pull "/sdcard/Android/data/com.riftbound.packtally/files/collection-*.json"` → open on laptop → valid JSON containing `sets` AND `loose_scans` arrays.
25. **Manual backup** — Settings → Backups → Back up now. Toast shows the path. File appears in `/sdcard/Android/data/com.riftbound.packtally/files/backups/`. Open the zip externally → contains `database.db`, `prefs.json` (no API key), `cache/`, `manifest.json`.

## UI polish

26. **USD ↔ AUD currency switch** — Settings → Currency segmented buttons. Toggle. Every screen with prices (Home, Pack, Box, Collection, Quick Scan sheet) re-renders in the new currency. Conversion rate field appears for non-USD.
27. **Dark mode renders correctly** — Phone settings → Display → Dark mode. App switches automatically. No white-on-white text. Check sticky headers and bottom sheets.
28. **Screen rotation locked to portrait** — Try rotating the device on any screen. App stays portrait (declared `android:screenOrientation="portrait"` in manifest for MainActivity).

## Edge cases

29. **Back button mid-pack** — Pack tab with cards filled → press hardware back. There's no confirmation dialog, but the session is safely persisted in Room — re-enter the Pack tab and your data is still there.
30. **50-card session — no UI jank** — Quick Scan 50 cards in a row. Scrolling Collection should stay smooth. If you see jank, check `adb logcat | grep -i 'choreographer\|skipped frames'`. Heap should stabilize — see `docs/PERFORMANCE.md`.

---

**Cold start sanity** (separate from this 30): cold-start to first usable screen should be under 2s. Measure with `adb shell am start -W -n com.riftbound.packtally/.MainActivity` and read the `TotalTime` field. Document the actual number in `docs/PERFORMANCE.md`.
