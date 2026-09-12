# Reel-share embedded-thumbnail cache key fix (consistency)

## Correction to prior assumption
Checked the flagged 240×240 at the reel-SHARE card thumbnail (the
~165x293dp DM-style forwarded-reel card). Turned out `reelCardPx(ctx)`
— a density-aware helper already built for exactly this card (fixes an
earlier hardcoded 330x474px bug, see its own comment) — was already in
use for the URL-loaded and Firebase-fetched branches of this thumbnail.
Only the base64-embedded-thumbnail branch was still keyed on a flat
240×240.

## Nuance
`decodeB64ThumbAsync()` decodes the embedded bytes at full resolution
regardless of the key passed in — the dimensions only affect the
`DECODED_BITMAP_CACHE` key, not the actual decode size. So this wasn't a
blurry/oversized-decode bug like the seen-bubble one; it meant the same
reel's thumbnail could land in the cache under two different keys
depending on whether the b64 branch or the URL branch bound it first —
missed cache-sharing, not a quality issue.

## Fix
The b64 branch's pool key now uses `reelCardPx(ctx)` too, matching the
URL/Firebase branches, so all three share one cache entry per reel.

Files touched:
- feature-chat/src/main/java/com/callx/app/conversation/MessagePagingAdapter.java
