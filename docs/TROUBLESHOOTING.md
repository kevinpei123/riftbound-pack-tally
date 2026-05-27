# Troubleshooting

## OCR

**Symptom:** A card keeps getting identified as the wrong one, or "Could not identify card".

- First try: **tap the cell in Pack/Box grid** → manual-correction sheet → search by name → Swap. Corrections null out the price so the next Submit refreshes it.
- If many cards in a row are misread (especially foil/signature): Settings → toggle **Force OCR preprocessing** on. This applies grayscale + 1.5× contrast before every recognition pass. Otsu binarization still kicks in automatically on the retry path.
- For one-off "I just want to type the name": Quick Scan tab → after 3 OCR failures the "Type the card name instead →" button appears at the top. Collection has the same flow via "+ Add card".

**Symptom:** App stuck on the "Setting up card database" loading screen.

- Riftcodex (`https://api.riftcodex.com`) may be down or unreachable.
- Check `adb logcat -s FirstLaunch CardDbSync` for the actual exception.
- The FirstLaunchScreen surfaces a Retry button after a failure.
- If Riftcodex stays down, see `docs/API_NOTES.md` → "Backup source playbook" — RiftScribe stub is ready to wire.

**Symptom:** OCR reads `UNL . 156/219` (with a period between SET and number) and identifies nothing.

- Shouldn't happen on this build — the parser absorbs space, period, middle-dot, bullet, hyphen, underscore, or nothing between SET and NUM. If it does, file a regression with the exact OCR output (visible in `adb logcat -s CardOcrParser` if you bump it to Verbose).

## Sync / database state

**Symptom:** App shows 0 cards even after sync supposedly succeeded.

- MainActivity gates on `CardDatabase.size > 0` as well as the
  `cards_synced_at` flag — a "synced but empty" state self-heals by routing
  back to FirstLaunchScreen. If it doesn't, force a reset:
  Settings → Reset all data → tap Reset → app re-syncs from scratch.
- `CardDbSync.runFullSync()` throws on an empty result, so a healthy code
  path can't produce this state. Check `adb logcat -s CardDbSync` for the
  failure message — most likely "Riftcodex returned 1064 cards but none
  matched the expected schema" (envelope or `riftbound_id` shape changed).

**Symptom:** "Re-sync from Riftcodex" button does nothing visible.

- Settings → check the card count. If it changed, sync worked silently.
- Otherwise `adb logcat -s CardDbSync` will show the HTTP error.

## Pricing

**Symptom:** "API key rejected" or every Submit fails with 401.

- Settings → JustTCG API key field. Re-paste, watch for trailing whitespace. The field saves on every keystroke; tap outside to commit.
- Validate the prefix: JustTCG keys start with `tcg_`. Settings shows an inline error if the prefix is wrong.

**Symptom:** "Quota exhausted" — one of three buckets.

JustTCG free tier has three independent limits. Check which is full in
Settings → Quota:

- **Monthly (1,000/month):** wait until next billing cycle. Raise Cache TTL toward 24 h.
- **Daily (100/day):** wait until UTC midnight.
- **Per-minute (10/min):** the app auto-throttles at 7/10 with a 6-second back-off.
- Settings → "Cache-only mode" stops all network calls for the session.

**Symptom:** Submit returns "Pricing failed" with no further detail.

- `adb logcat -s JustTcgPricingRepository` shows the underlying exception.
- Network down → check Wi-Fi / mobile.
- API key invalid → see above.
- All entries in the batch had `tcgplayerId = ""` → check `adb logcat -s CardDbSync` for "Dropped … cards lacking tcgplayer_id" warnings; the affected cards never got priced because they have no JustTCG join key.

**Symptom:** Prices look wrong for AUD (or other non-USD).

- Settings → Currency segmented row should match your preference. Conversion rate field appears below. The rate is hard-coded — update it manually when it drifts. Default for AUD is 1.55.

## Camera

**Symptom:** Camera goes black after switching from Scanner ↔ Quick Scan.

- Fixed in this build. Each `CameraScreen` instance tracks the `Preview` and `ImageCapture` use-cases it bound and only unbinds those on dispose. If you see this regression, check `CameraScreen.kt` for any reintroduced `provider.unbindAll()` in the `DisposableEffect.onDispose` block.

**Symptom:** Camera black screen, app feels frozen on first open.

- Camera permission may have been denied. Settings → Apps → Riftbound Pack Tally → Permissions → Camera → Allow.
- EMUI sometimes shows a per-app camera popup the first time — accept it.
- Cold restart the app (force stop + relaunch). If still black, check `adb logcat -s CameraScreen` for binding errors.

**Symptom:** Capture FAB does nothing.

- `ImageCapture` use case may not be bound yet. Wait for the camera preview to fully render before tapping. The FAB is greyed-out (`surfaceVariant` colour) until `imageCapture != null`.

## Sideload

**Symptom:** `adb` doesn't see the P30 Pro.

- USB mode must be **File transfer (MTP)**, not HiSuite / charging-only.
- Developer options → **Allow HiSuite to use HDB** must be OFF.
- First connect: accept the RSA fingerprint prompt on the phone.
- Try `adb kill-server && adb start-server`.

**Symptom:** `INSTALL_FAILED_VERSION_DOWNGRADE`.

- Debug APK's `versionCode` is lower than what's installed. `adb uninstall com.riftbound.packtally` then reinstall.

**Symptom:** App icon doesn't appear after install.

- Check `adb logcat -s PackageManager` for the install error. EMUI's "Verify apps via USB" might be enabled — Developer options → disable.

## Collection / data

**Symptom:** Tapped "−" on a Collection row but nothing happened, or toast says "Couldn't find … to remove".

- The list may be out of date — pull-to-refresh or tap to a different tab and back. `RemoveNotFound` fires only when the underlying row has been deleted by another flow between render and tap.

**Symptom:** Removed a card from Collection but Pack tab still shows it.

- The Pack tab caches the active box in memory. Tap to a different tab and back to trigger `refreshFromDisk()`. Or restart the app. The disk state is authoritative — `adb shell sqlite3 ...` will confirm.

**Symptom:** Lost an in-progress box.

- Re-open the app. `PackViewModel.init` restores the most recent unfinished session from Room. If it's not there, check `adb logcat -s PackViewModel | grep Restore`. Manual backup at Settings → Backups can recover if you have a recent .zip.

**Symptom:** Box mode shows packs in the wrong order.

- PackSessions inside a box are ordered by their `position` column. Verify with `adb shell sqlite3 /data/data/com.riftbound.packtally/databases/session.db "SELECT id, boxId, position FROM pack_sessions ORDER BY position"`.

**Symptom:** Collection total doesn't match Pack/Box subtotals.

- Quick Scan loose scans + manually-added cards are aggregated into the same Collection groups. Filter by "Manual / Quick only" to isolate.
- Cards with `price = null` (not yet submitted) contribute $0 to totals.
- Verify the currency conversion rate hasn't changed mid-session.

## Quick Scan submit button visibility

**Symptom:** I added a card via Collection's "+ Add card" but Quick Scan doesn't show a Submit button.

- The button shows when `stats.pendingCount > 0`. The view-model seeds this from the database on init via `looseScans.getPending()`, so it should appear as soon as you tab to Quick Scan. If it doesn't, force-stop the app and reopen — the seed runs once per VM lifecycle.
- Alternative: Submit directly from Collection (the same button appears in the Collection header when pending > 0).

## When to nuke

`Settings → Reset all data` wipes Room (sessions, loose scans, cards) +
cache + DataStore + in-memory session.

Use when:
- Suspicious about DB corruption after a force-stop.
- Want to test the fresh-install flow without `adb uninstall`.
- The sync gate is misbehaving (you're stuck on the FirstLaunchScreen with no retry path).

Backup first (Settings → Backups & restore → Back up now).
