package com.riftbound.packtally.core.carddb

/**
 * RiftScribe (`https://riftscribe.gg`) backup card-data client. Kept as a
 * skeleton so we can swap providers quickly if Riftcodex disappears.
 *
 * The RiftScribe API surface is not 1:1 with Riftcodex:
 *
 *   Riftcodex                                     RiftScribe (sketch)
 *   ──────────                                    ───────────────────
 *   GET /cards?size=100&page=N                    GET /api/cards (paginated; params differ)
 *   GET /cards/riftbound/{id}                     GET /api/cards/by-riftbound/{id}
 *   tcgplayer_id present on every card            availability needs verification
 *   metadata.signature                            mapping unknown — may need a different field
 *
 * To switch over: implement the two methods, then in `App.kt` swap the
 * `RiftcodexClient` instance for `RiftscribeClient`. The rest of the code
 * (CardDbSync, CardEntity mapping) is provider-agnostic.
 *
 * Not currently wired into the app — calling either method throws.
 */
class RiftscribeClient {
    suspend fun fetchAllCards(): List<RiftcodexCardDto> =
        throw NotImplementedError("RiftScribe client is a stand-by fallback — implement when needed.")

    suspend fun lookupByRiftboundId(id: String): RiftcodexCardDto? =
        throw NotImplementedError("RiftScribe client is a stand-by fallback — implement when needed.")
}
