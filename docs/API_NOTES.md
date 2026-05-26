# API Notes

Two external services power the app after the JustTCG migration.

## Riftcodex (card data)

- **Base URL:** `https://api.riftcodex.com/`
- **Auth:** none. No API key required.
- **Rate limit:** none documented. Be a polite citizen: full-sync only once per
  app launch, then every 7 days in the background.

### Endpoints we use

| Method + path | Purpose |
|---|---|
| `GET /cards?size=100&page=N` | Paginated full catalogue. Walk pages until response < 100. |
| `GET /cards/riftbound/{id}` | Reverse lookup by Riftbound ID (e.g. `OGN-011`). Used only if the local DB misses. |
| `GET /cards/tcgplayer/{tcgplayer_id}` | Reverse lookup by JustTCG join key. Not currently called — included for future "I know the tcgplayerId, what card is it?" flows. |

### Key fields

Every card carries:

```
id                          Riftcodex internal ID
name
riftbound_id                "OGN-011" — what OCR reads off the card
tcgplayer_id                THE JOIN KEY into JustTCG
collector_number            integer (we prefer riftbound_id for OCR matching)
attributes { energy, might, power }    nullable (spells have no might)
classification { type, supertype, rarity, domain[] }
text { rich, plain, flavour }
set { set_id, label }
media { image_url, artist, accessibility_text }
metadata {
  clean_name,
  updated_on,
  alternate_art (bool),
  overnumbered (bool),
  signature (bool)          ← used to set hasSignatureVariant locally
}
```

Cards lacking `tcgplayer_id` are dropped during sync — without it we can't price them via JustTCG.

### Backup source: RiftScribe

RiftScribe (`https://riftscribe.gg`) is listed as an alternative community source. The API shape differs (see `core/carddb/RiftscribeClient.kt` for the sketched differences). Currently a `TODO()`-shaped stub; swap when Riftcodex actually goes down.

## JustTCG (pricing)

- **Base URL:** `https://api.justtcg.com/v1/`
- **Auth:** header `X-API-Key: tcg_…`
- **Free tier limits:**
  - 1,000 requests / month
  - 100 / UTC day
  - 10 / rolling minute
  - Up to 20 items per batch POST

### Endpoint we use

`POST /cards` with body:

```json
[
  { "tcgplayerId": "1234567", "condition": "Near Mint", "printing": "Normal" },
  { "tcgplayerId": "1234568", "condition": "Near Mint", "printing": "Foil" }
]
```

Response is `{ "data": [...], "_metadata": { "apiRequestsRemaining", "apiDailyRequestsRemaining", "apiRateLimit" } }`. The `_metadata` block is authoritative — we use it to correct the local quota tracker on every call.

### Variant mapping

The user picks Standard / Foil / Signature on the variant sheet. We map to JustTCG's `(printing, condition)` filter:

| User picks | `printing` | `condition` | Notes |
|---|---|---|---|
| STANDARD  | `Normal`   | `Near Mint` | |
| FOIL      | `Foil`     | `Near Mint` | |
| SIGNATURE | `Foil`     | `Near Mint` | JustTCG has no `Signature` printing; foil is the closest market data. Premium for the actual signature is not captured by raw market data and would need a manual override (deferred). |

### Variant pick within a response

JustTCG may return multiple variants for one tcgplayerId (different languages, alt arts). We pick:
1. First English variant matching `(printing, condition)`.
2. Fall back to the highest-priced variant in the matching set.

### Free-tier math for the box-day workflow

- **One 14-card pack:** 1 POST (all 14 in one batch). After cache TTL of 6h, identical re-scans = 0 calls.
- **One 24-pack box, all cards unique:** 24 POSTs (one per pack). 336 items / 14 items per pack = 24 packs = 24 calls.
- **Collection pull-to-refresh, 200 cards:** `ceil(200 / 20)` = 10 POSTs.

Even a chaotic day of opening 5 boxes + 3 collection refreshes = ~150 calls. Well under the 1000/month cap.

The 10/min cap is the immediate constraint during a rapid box-opening session. At 7/10 used the client auto-injects a 6-second back-off before the next call.

### Error handling

| HTTP | Code | Local mapping |
|---|---|---|
| 400 | `INVALID_REQUEST` | `BadRequest` (not retried) |
| 401 | `MISSING_API_KEY` / `INVALID_API_KEY` | `AuthFailed` (show user "Re-enter key") |
| 429 | `RATE_LIMIT_EXCEEDED` | `RateLimited` (retry once with 6s back-off) |
| 429 | `DAILY_LIMIT_EXCEEDED` | `RateLimited` (don't retry) |
| 429 | `REQUEST_LIMIT_EXCEEDED` | `RateLimited` (don't retry, suggest upgrade) |
| 500 | server error | `ServerError` (retry once) |

## The join key

```
                 OCR reads "OGN-011"
                          │
                          ▼
        CardDatabase.lookupByNumber("OGN-011")
                          │
                          ▼
     RiftboundCard { id, name, …, tcgplayerId: "1234567" }
                          │
                          ▼
       PriceRequest(tcgplayerId="1234567", variant=FOIL)
                          │
                          ▼
     JustTcgClient.postCards([{ tcgplayerId: "1234567", … }])
```

Without `tcgplayer_id` on every card we sync, the whole pricing pipeline is broken. CardDbSync drops Riftcodex cards lacking this field at ingest time and logs a warning.

## Backup source playbook

Riftcodex is marked "WIP" in their docs. If it disappears or starts returning stale data:

1. Settings → toggle the (deferred) "Use RiftScribe (backup card source)" switch.
2. App swaps to `RiftscribeClient` for the next sync.
3. If RiftScribe also unavailable: extract `cards.json` from your last backup (`Settings → Backups → Open backups folder`) and side-load it manually via adb push to the `assets/` directory of a custom build. Document path in `docs/TROUBLESHOOTING.md`.
