# QA Checklist

## Install and Sync

- Fresh install.
- First-launch Riftcodex sync succeeds with nonzero card count.
- Re-sync from Settings shows progress and result.
- Reset all data returns to first-launch sync.

## Scan Session

- Start new scan session from Home.
- Continue active session from Home.
- Scan a card into Current.
- Rapid Mode records Standard immediately.
- Manual add to Current.
- Undo last scan.
- Remove one entry.
- Change variant; price clears and status returns to pending/unpriceable.
- Clear session requires confirmation.
- End session without pricing; cards remain in Collection.
- Force-stop mid-session; reopen and verify active session persists.

## Pricing

- No pricing call appears in logcat when recording a scan.
- Submit 18 pending cards: one batch.
- Submit 46 pending cards: three batches, 20/20/6.
- Submit 100 pending cards: five batches.
- Cache hit does not increment quota.
- Missing `tcgplayer_id` rows show unpriceable.
- 401 surfaces API-key message.
- 429 surfaces quota/rate-limit message.
- Failed batch leaves entries retryable.

## Collection

- Header shows total cards, unique cards, estimated value, pending prices.
- Search by name and collector number.
- Sort by name, set, collector, rarity, domain, quantity, value, recent.
- Group by set, rarity, domain, variant, none.
- Filter pending, variant, set, rarity, domain.
- Manual add works.
- Remove one copy works.
- Export JSON works and does not include API key.
- Dark mode and large text remain usable.

## Settings and Backup

- Enter JustTCG key.
- Cache-only mode blocks network pricing calls.
- Refresh exchange rate succeeds online.
- Failed exchange-rate refresh keeps cached rate and shows warning.
- Backup zip includes database, cache, sanitized prefs, manifest.
- Backup prefs exclude API key.

## Camera

- Permission grant path works.
- Permission deny/retry path works.
- Scan <-> Current tab switching does not leave preview black.
- Rotate/background/foreground does not crash.
- Long 50-card session does not show monotonic heap growth.
