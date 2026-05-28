# Troubleshooting

## OCR Misses

- Retry with the collector number inside the guide.
- Reduce glare and motion blur.
- Toggle Settings -> Force OCR preprocessing for foils/signatures.
- Use Current -> Add to type the card name.
- Re-sync the card database if a new set is missing.

## Pricing

- Enter a JustTCG key in Settings before submitting prices.
- Scans are intentionally unpriced until Submit is tapped.
- Pending/failed entries remain retryable.
- Cache-only mode blocks network calls but still displays cached prices.
- Cards without `tcgplayer_id` show as unpriceable.

## Exchange Rates

- Currency refresh uses Frankfurter and does not require an API key.
- If refresh fails, the last cached USD-to-target rate remains in use.
- Settings shows the last updated time, source, and any refresh warning.
- Currency refresh failure should not block scanning, collection, or pricing.

## Sync

- If the card DB is empty, MainActivity routes back to first-launch sync.
- `CardDbSync` refuses to commit an empty Riftcodex response.
- Use Settings -> Re-sync from Riftcodex before scanning a newly released set.

## Camera

- Confirm camera permission in Android app settings.
- If preview is black after tab switching, force-stop and reopen, then capture
  `adb logcat -s CameraScreen ScannerViewModel`.
- Keep the phone in portrait; the guide is tuned for a 5:7 card in 9:16 preview.

## Data Recovery

- v4 keeps legacy Pack/Box/loose tables for recovery.
- Legacy loose scans migrate into `Migrated loose scans`.
- Legacy pack JSON migrates at startup into completed scan sessions when it can
  be decoded.
- Backup zip excludes the API key by design.
