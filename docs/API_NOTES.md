# API Notes

## Riftcodex

- Base URL: `https://api.riftcodex.com/`
- Auth: none.
- Used for card metadata sync into Room.

Endpoints:

- `GET /cards?size=100&page=N`
- `GET /cards/riftbound/{id}` reserved fallback
- `GET /cards/tcgplayer/{tcgplayer_id}` reserved fallback

The list envelope currently uses `items`. The client also accepts `data` and
`cards` as fallbacks. `CardDbSync` refuses to wipe the local DB if the response
maps to zero valid cards.

Important fields:

- `riftbound_id`: normalized into `SET-NUM[letter]`, e.g. `unl-060a-219` ->
  `UNL-060a`.
- `tcgplayer_id`: required JustTCG join key. Cards missing it are dropped and
  logged.
- `classification.rarity`: persisted for Collection sorting/filtering.
- `classification.domain[]`: persisted as `cards.domains` for Domain filters.
- `media.image_url`: used by Collection thumbnails.

## JustTCG

- Base URL: `https://api.justtcg.com/v1/`
- Auth: `X-API-Key: tcg_...`
- Pricing endpoint: `POST /cards`
- Max batch size: 20 cards.

Pricing remains batched-only:

| Caller | Trigger |
|---|---|
| `SessionRepository.submitPendingPrices(..., sessionId)` | Current Session submit |
| `SessionRepository.submitPendingPrices(..., sessionId = null)` | Collection submit all pending |

Scan recording never calls JustTCG.

Batch examples:

- 18 pending entries -> 1 POST
- 46 pending entries -> 3 POSTs: 20, 20, 6
- 100 pending entries -> 5 POSTs

`CachedPricingRepository` sits before the HTTP repository. Cache hits return
without a network request and do not burn quota. Network failures leave entries
`FAILED` so retry is safe.

Variant mapping:

| App variant | JustTCG printing | Condition |
|---|---|---|
| Standard | Normal | Near Mint |
| Foil | Foil | Near Mint |
| Signature | Foil | Near Mint |

Signature uses Foil/Near Mint because JustTCG does not expose a separate
Signature printing.

## Frankfurter Currency Rates

- Base URL: `https://api.frankfurter.dev/`
- Auth: none.
- Endpoint used: `GET /v2/rates?base=USD&quotes=AUD` with the target currency
  substituted.

Frankfurter's current public docs say the API lives at
`api.frankfurter.dev`, requires no API key, and supports `base` plus `quotes`
query parameters for latest rates: https://frankfurter.dev/docs/

The app stores:

- rate
- base currency
- target currency
- fetched timestamp
- source name

If refresh fails, the app keeps the cached rate and records a warning in
DataStore. Scanning, collection loading, and JustTCG pricing do not wait on
currency refresh.
