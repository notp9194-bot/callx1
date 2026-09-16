# Advance #4 — ThumbHash decode + blur off the main thread

## Problem
`ThumbHashPlaceholder.get()` was called directly from `MessagePagingAdapter`'s
bind path (3 call sites — image, video, video/E2E-decrypted-hash). On an L1
cache miss this ran `ThumbHash.decode()` synchronously on the main thread,
which can contribute to jank/frame-drops during a fast RecyclerView fling.

## Fix
- **`ThumbHashPlaceholder.getAsync(hash, w, h, callback)`** (new):
  - L1 cache **hit** → `callback.onReady()` fires immediately, inline, same
    thread — zero added latency vs. the old `get()` for the common case.
  - L1 **miss** → decode is submitted to a small dedicated `DECODE_POOL`
    (`fixedThreadPool`, `max(2, cores/2)`), kept separate from the existing
    disk-persist `IO` executor so scroll-triggered decodes never queue
    behind disk writes. Result is cached + persisted exactly as before, then
    posted back to the main thread via a `Handler(Looper.getMainLooper())`.
  - Synchronous `get()` is untouched — still used by `preload()`.
- **`MessagePagingAdapter`**: all 3 bind-time call sites switched to
  `getAsync`, guarded by the existing `h.canvasBindToken != myToken` check
  (same pattern already used by `resolveVideoBlurHashAsync` /
  `resolveFullMediaKeyAsync`) so a row recycled/rebound mid-decode just
  drops the stale result instead of painting the wrong bubble.

## Net effect
Cache hits (the vast majority once `warmUpFromDisk()` has run) behave
exactly as before — instant, synchronous. Only a genuine cold decode moves
off the main thread, closing the last main-thread cost in the ThumbHash
placeholder pipeline (#1 disk cache, #2 RGB_565, #3 native blur already
shipped).
