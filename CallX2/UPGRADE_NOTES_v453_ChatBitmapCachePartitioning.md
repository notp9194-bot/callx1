# v453 — Chat bitmap-cache partitioning

## What changed

- Replaced the single chat-wide decoded bitmap LRU with purpose-partitioned,
  byte-sized LRUs in `MessagePagingAdapter`:
  - main image/video/reel media
  - status-seen and reel-seen thumbnails
  - media-grid cells
  - GIFs
  - stickers
  - location maps
  - reply thumbnails
- Kept the existing dedicated link-preview LRU independent from all of the
  above.
- Routed embedded Base64 thumbnail decoding through the matching category
  cache instead of the old shared pool.
- Kept the overall in-memory budget bounded to approximately the previous
  general-pool size plus the existing link-preview budget; each pool is
  byte-sized using `Bitmap.getByteCount()`, so large media cannot evict
  unrelated small chat thumbnails.

## Scope

This upgrade targets cache contention only. It does not change the separate
CustomTarget lifecycle work, disappearing-message expiry behavior, or
prefetch behavior.

## Validation

The app was not built or run, as requested. Static reference checks were done
to confirm that no production Java/Kotlin call-site still uses the removed
shared `DECODED_BITMAP_CACHE` identifier.