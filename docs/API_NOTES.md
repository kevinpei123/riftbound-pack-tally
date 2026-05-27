# API Notes

Two external services power the app: Riftcodex (card metadata) and JustTCG
(pricing). Pricing is **always batched** now — there's no per-scan call.

## Riftcodex (card data)

- **Base URL:** `https://api.riftcodex.com/`
- **Auth:** none. No API key required.
- **Rate limit:** none documented. Be a polite citizen: full-sync only once per
  app launch when needed.

### Endpoints we use

| Method + path | Purpose |
|---|---|
| `GET /cards?size=100&page=N` | Paginated full catalogue. Walk pages until response < 100. Hard ceiling at 50 pages (5,000 cards). At time of writing the catalogue is ~1,064 cards = 11 pages. |
| `GET /cards/riftbound/{id}` | Reverse lookup by Riftbound ID. Used as a fallback if the local DB misses (not currently called). |
| `GET /cards/tcgplayer/{tcgplayer_id}` | Reverse lookup by JustTCG join key. Not currently called — reserved for future flows. |

### List envelope (LIVE shape)

```json
{
  "items": [ { …card… }, … ],
  "total": 1064,
  "page": 1,
  "size": 100,
  "pages": 11
}
```

`RiftcodexListResponse` accepts `items` (current), `data` (older builds), and
`cards` (oldest) in that priority order. Anything else throws on parse and
`runFullSync` refuses to commit an empty DB.

### Per-card fields

```
id                          Riftcodex internal mongoid
name
riftbound_id                "unl-060a-219" — lowercase, set-num[letter]-total
tcgplayer_id                THE JOIN KEY into JustTCG
collector_number            integer (we don't rely on it for OCR matching;
                            we parse riftbound_id instead)
attributes { energy, might, power }    nullable (spells have no might)
classification { type, supertype, rarity, domain[] }
text { rich, plain, flavour }
set { set_id, label }       set_id is the canonical 3-letter code (e.g. "UNL")
media { image_url, artist, accessibility_text }
metadata {
  clean_name,
  updated_on,
  alternate_art (bool),
  overnumbered (bool),
  signature (bool)          ← used to set hasSignatureVariant locally
}
```

Cards lacking `tcgplayer_id` are dropped during sync — without it we can't
price them via JustTCG. `CardDbSync` logs `Dropped N cards lacking …` if any.

### How `riftbound_id` becomes the canonical key

The DTO mapping in `CardDbSync.toEntityOrNull` parses
`riftbound_id` into `[set, num+letter?, total]` parts:

```
"unl-060a-219"  →  setCode = "UNL", numWithSuffix = "060a"
                →  CardEntity(setCode="UNL", collectorNumber="UNL-060a")

"ogn-181-298"   →  setCode = "OGN", numWithSuffix = "181"
                →  CardEntity(setCode="OGN", collectorNumber="OGN-181")
```

The OCR parser (`CardOcrParser`) outputs the same `SET-NUM` shape (uppercase,
no total, alt-art letter preserved IF found, signature `*` stripped) and
`CardDatabase.lookupByNumber` matches on it.

### Known set codes (lowercase / uppercase agnostic)

`OGN, OGS, ARC, SFD, UNL, FND` — `KNOWN_SETS` in both
`CardOcrParser.kt` and `ScannerViewModel.kt` / `QuickScanViewModel.kt`. The
OCR parser's Pass 1 prefers a match in `KNOWN_SETS`; Pass 2 falls back to any
2–4 letter prefix so future sets work without a code change.

### Self-healing sync

`CardDbSync.runFullSync()`:

- Fetches every page.
- Maps DTOs → entities, dropping anything missing `tcgplayer_id`.
- **Throws if the entity list is empty** (so we don't `deleteAll()` and mark
  synced on an empty result — that's how the user got stranded on the
  previous build).
- `cardDao.deleteAll()` + `upsertAll(entities)` + write `cards_synced_at`.

`MainActivity` also gates on `CardDatabase.size > 0` so a previously-broken
"synced but empty" install routes back through FirstLaunchScreen.

### Backup source: RiftScribe

RiftScribe (`https://riftscribe.gg`) is listed as an alternative community
source. See `core/carddb/RiftscribeClient.kt` for the sketched differences.
Currently a `TODO()`-shaped stub; swap when Riftcodex actually goes down.

## JustTCG (pricing)

- **Base URL:** `https://api.justtcg.com/v1/`
- **Auth:** header `X-API-Key: tcg_…`
- **Free tier limits:**
  - 1,000 requests / month
  - 100 / UTC day
  - 10 / rolling minute
  - Up to 20 items per batch POST

### Batched-only pricing model

There is **no** per-card price call anywhere in the app. The only paths that
hit JustTCG are:

| Caller | Trigger | Payload |
|---|---|---|
| `PackViewModel.submitPack` / `completePack` | User taps Submit (or Submit & Complete) on Pack screen | Every unpriced entry in the active pack — typically 14 |
| `LooseScanRepository.submitPendingPrices(pricing)` | User taps "Submit N cards for pricing" on Quick Scan / Collection | Every `loose_scans` row with empty `priceJson` |

Both call `PricingRepository.priceMany(List<PriceRequest>)`, which
`JustTcgPricingRepository` chunks into ≤20-item batches. Each chunk = one
HTTP POST = one quota tick.

A full 24-pack box submitted one pack at a time = 24 HTTP calls. The
Collection "Submit pending" path can submit hundreds of cards in
`ceil(N/20)` calls.

### Endpoint we use

`POST /cards` with body:

```json
[
  { "tcgplayerId": "1234567", "condition": "Near Mint", "printing": "Normal" },
  { "tcgplayerId": "1234568", "condition": "Near Mint", "printing": "Foil" }
]
```

Response is `{ "data": [...], "_metadata": { "apiRequestsRemaining", "apiDailyRequestsRemaining", "apiRateLimit" } }`. The `_metadata` block is
authoritative — we use it to correct the local quota tracker on every call.

### Variant mapping

The user picks Standard / Foil / Signature on the variant sheet. We map to
JustTCG's `(printing, condition)` filter:

| User picks | `printing` | `condition` | Notes |
|---|---|---|---|
| STANDARD  | `Normal`   | `Near Mint` | |
| FOIL      | `Foil`     | `Near Mint` | |
| SIGNATURE | `Foil`     | `Near Mint` | JustTCG has no `Signature` printing; foil is the closest market data. |

### Variant pick within a response

JustTCG may return multiple variants for one tcgplayerId (different languages,
alt arts). We pick:
1. First English variant matching `(printing, condition)`.
2. Fall back to the highest-priced variant in the matching set.

### Free-tier math

- **One 14-card pack submitted:** 1 POST (all 14 in one batch).
- **One 24-pack box, one pack at a time:** 24 POSTs.
- **100 manually-added loose scans submitted in bulk:** `ceil(100/20)` = 5 POSTs.

Even a chaotic day of opening 5 boxes + Collection refreshes is well under the
1,000/month cap. The 10/min cap is the tighter constraint during a rapid
box-opening session — `JustTcgPricingRepository` auto-injects a 6-second
back-off at 7/10 used.

### Error handling

| HTTP | Code | Local mapping |
|---|---|---|
| 400 | `INVALID_REQUEST` | request failed; user sees Toast |
| 401 | `MISSING_API_KEY` / `INVALID_API_KEY` | request failed; user sees Toast asking to re-enter key |
| 429 | `RATE_LIMIT_EXCEEDED` | `RateLimitedException` |
| 429 | `DAILY_LIMIT_EXCEEDED` | `RateLimitedException` |
| 429 | `REQUEST_LIMIT_EXCEEDED` | `RateLimitedException` |
| 500 | server error | request failed; user sees Toast |

## The join key

```
                 OCR reads "UNL 181/219"
                          │
                          ▼
                  CardOcrParser → "UNL-181"
                          │
                          ▼
        CardDatabase.lookupByNumber("UNL-181")
                          │
                          ▼
     RiftboundCard { id, name, …, tcgplayerId: "684500" }
                          │
                          ▼
       PriceRequest(tcgplayerId="684500", variant=FOIL)
                          │
                  (batched with the other 13 cards in the pack)
                          ▼
     JustTcgClient.postCards([{ tcgplayerId: "684500", … }, …])
```

Without `tcgplayer_id` on every card we sync, the whole pricing pipeline is
broken for that card. CardDbSync drops Riftcodex cards lacking this field at
ingest time and logs a warning.

## Backup source playbook

Riftcodex is marked "WIP" in their docs. If it disappears or starts returning
stale data:

1. Settings → toggle the (deferred) "Use RiftScribe (backup card source)" switch.
2. App swaps to `RiftscribeClient` for the next sync.
3. If RiftScribe also unavailable: extract a recent backup zip and restore.
