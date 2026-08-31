# v222 — Cache & Storage dashboard wiring fix

## Problem
`CacheStatsActivity` ("Settings → Storage & Cache") only reads
`com.callx.app.cache.CacheManager` → `MemoryCache` / `DiskCache`. That tier
is completely separate from the actual bitmap-loading path used by chat —
`MessagePagingAdapter`'s private static `DECODED_BITMAP_CACHE` +
`AVATAR_BITMAP_CACHE` + Glide's own internal disk cache. Real thumbnail
traffic never touched `CacheManager` at all, so the dashboard showed
"0% hits" / "0 B used" / "Never cleared" even after 1,340 real messages —
not because caching wasn't happening, but because the dashboard was
watching the wrong tier.

## Fix
Wired the five most data-heavy bind paths in `MessagePagingAdapter.java`
so every pool hit / freshly-decoded bitmap is also mirrored into
`CacheManager`'s `MemoryCache` (real hit/miss counters) and `DiskCache`
(real bytes-on-disk):

- Reel-seen bubble thumbnail ("Watched your reel")
- Status-seen bubble thumbnail ("Seen your status")
- Normal chat image + video thumbnails
- Reel-share bubble avatar + thumbnail
- Contact-share avatar

Added two small static helpers, `dashboardRecordHit()` and
`dashboardRecordDecoded()`. These are pure bookkeeping — they run
alongside the existing `DECODED_BITMAP_CACHE`/Glide fast path, which is
unchanged, so scroll performance is unaffected. Disk-cache writes
(JPEG-compressed thumbnail bytes) happen on a dedicated single-thread
executor so the main thread only does the (cheap) `compress()` call for
a small thumbnail-sized bitmap.

## Not in scope
Only the five items above were wired, per request. Sender/group avatars,
chat wallpaper, the top-of-chat profile-card avatar, GIFs, and stickers
still bypass `CacheManager` — they're real Glide-disk-cached, they just
still won't show up on the Cache & Storage screen.
