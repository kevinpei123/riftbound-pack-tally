# Troubleshooting

## OCR

**Symptom:** A card keeps getting identified as the wrong one, or "Could not identify card".

- First try: **tap the cell in Pack/Box grid** → manual-correction sheet → search by name → Swap.
- If many cards in a row are misread (especially foil/signature): Settings → toggle **Force OCR preprocessing** on. This applies grayscale + 1.5× contrast before every recognition pass. Otsu binarization still kicks in automatically on the retry path.
- For one-off "I just want to type the name": Quick Scan tab → after 3 OCR failures the "Type the card name instead →" button appears at the top. (Pack mode doesn't surface this yet — Phase 3 deferred.)

**Symptom:** App crashes on first launch with a serialization error.

- Likely `app/src/main/assets/cards.json` is missing or malformed.
- Rebuild it: `pip install requests beautifulsoup4 && python3 scripts/build_cards_json.py`. The script writes to the assets directory; reinstall the APK to pick up the new file.

## Pricing

**Symptom:** "API key rejected" or every price call fails.

- Settings → tcgapi.dev API key field. Re-paste, watch for trailing whitespace. The field saves on every keystroke; tap outside to commit.
- Check the dashboard at tcgapi.dev that the key is still active (Hobby tier, not expired).

**Symptom:** "Daily quota hit. Resets in HH:MM."

- You've spent 1000/1000 requests today on tcgapi.dev. Cache hits don't count; only network calls do.
- Options:
  - Wait until UTC midnight — counter resets automatically.
  - In Settings → Cache TTL slider, bump higher (default 24h, max 48h). Subsequent scans within the window hit cache, not network.
  - Settings → flip "Cache-only mode" on for the rest of the session. Network calls return failure; cached entries still load.

**Symptom:** Prices wrong for AUD (or other non-USD).

- Settings → Currency segmented row should match your preference. Conversion rate field appears below. The rate is hard-coded — update it manually when it drifts. Default for AUD is 1.55.

## Install / sideload

**Symptom:** `adb` doesn't see the P30 Pro.

- USB mode must be **File transfer (MTP)**, not HiSuite / charging-only. Pull down notifications → tap USB notification → switch mode.
- Developer options → **Allow HiSuite to use HDB** must be OFF.
- First connect: accept the RSA fingerprint prompt on the phone.
- Try `adb kill-server && adb start-server`.
- Windows-specific: install Huawei's USB driver (or HiSuite, which bundles it).
- Linux-specific: add a udev rule for vendor `12d1`.

**Symptom:** `INSTALL_FAILED_VERSION_DOWNGRADE`.

- Debug APK's `versionCode` is lower than what's installed. `adb uninstall com.riftbound.packtally` then reinstall.

**Symptom:** App icon doesn't appear after install.

- Check `adb logcat -s PackageManager` for the install error. EMUI's "Verify apps via USB" might be enabled — Developer options → disable.

## Camera / runtime

**Symptom:** Camera black screen, app feels frozen.

- Camera permission may have been denied. Settings → Apps → Riftbound Pack Tally → Permissions → Camera → Allow.
- EMUI sometimes shows a per-app camera popup the first time — accept it.
- Cold restart the app (force stop + relaunch). If still black, check `adb logcat -s CameraScreen` for binding errors.

**Symptom:** Capture button does nothing.

- ImageCapture use case may not be bound yet. Wait for the camera preview to fully render (you should see live frames) before tapping.

## Data + state

**Symptom:** Lost an in-progress box.

- Re-open the app. `PackViewModel.init` restores the most recent unfinished session from Room. If it's not there, check `adb logcat -s PackViewModel | grep Restore`. Manual backup at Settings → Backups can recover if you have a recent .zip.

**Symptom:** Box mode shows packs in the wrong order.

- PackSessions inside a box are ordered by their `position` column. Verify with `adb shell sqlite3 /data/data/com.riftbound.packtally/databases/session.db "SELECT id, boxId, position FROM pack_sessions ORDER BY position"`.

**Symptom:** Collection total doesn't match Pack/Box subtotals.

- Quick Scan loose scans are aggregated into the same Collection groups. Filter to a single set/rarity to compare.
- Verify the currency conversion rate hasn't changed mid-session.

## Quota counter weirdness

**Symptom:** Counter shows different values than expected.

- Quota is keyed by UTC date. If you scanned at 10:55 PM Sydney time (UTC+10/11), that's near UTC midnight — counter rolls over.
- Force a re-read by toggling any setting. Or restart the app — `QuotaTracker.init` queries DataStore.

**Symptom:** "Reset counter (debug)" doesn't help.

- That button wipes today's counter to 0 in DataStore. If it doesn't take effect, check `adb logcat -s SettingsViewModel | grep "Quota reset"`.

## When to nuke

`Settings → Reset all data` wipes Room + cache + DataStore + in-memory session.
Use when:
- Suspicious about DB corruption after a force-stop.
- Want to test the fresh-install flow without `adb uninstall`.
- Trying to reset the quota counter and Reset counter (debug) somehow isn't working.

Backup first (Settings → Backups & restore → Back up now).
