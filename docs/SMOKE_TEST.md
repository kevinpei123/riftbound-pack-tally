# Smoke Test

Run after install on the phone/emulator.

## Commands

```bash
adb devices
./gradlew installDebug
adb shell am start -W -n com.riftbound.packtally/.MainActivity
adb logcat -s CardDbSync FirstLaunch CameraScreen CardOcrParser ScannerViewModel CurrentSessionVM CollectionViewModel JustTcgPricingRepository okhttp.OkHttpClient
```

## Flow

1. Fresh install opens first-launch sync if the card DB is empty.
2. Riftcodex sync completes with nonzero cards.
3. Bottom nav shows Home, Scan, Current, Cards, Set. Pack and Box are absent.
4. Home starts a new scan session.
5. Scan tab requests camera permission and shows live preview.
6. Capture one card, pick a variant, confirm it appears as Last added.
7. Current tab shows the card in the session list.
8. Add a manual card from Current.
9. Undo last scan.
10. Change a card variant; it becomes pending again.
11. Remove one card.
12. Submit pending prices only after entering a JustTCG key.
13. End the session; cards remain visible in Collection.
14. Collection search works by name/collector number.
15. Collection sort/group/filter controls work for set, rarity, domain, variant, pending.
16. Export JSON writes to external files.
17. Settings refreshes exchange rate and shows last updated/source.
18. Turn on airplane mode and refresh exchange rate; cached rate remains in use.
19. Toggle dark mode / large text and verify bottom nav labels fit.
20. Rapid tab switching Scan <-> Current does not black-screen the camera.
