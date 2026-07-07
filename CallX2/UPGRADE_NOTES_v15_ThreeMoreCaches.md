# v15 — Three remaining canvas-bubble perf gaps closed

Follow-up to `UPGRADE_NOTES_v107_TextLayoutPrecompute.md`. That pass cached
plain-text and poll-option `StaticLayout`s; this pass closes the three
gaps that were still open:

## 1. Audio waveform bar-height cache
`MessageBubbleCanvasView.generateAudioLevels(seed, count)` is a pure/
deterministic function (same seed → same bars) but was re-run from scratch
on every `bindAudio()`, including scroll-back to a voice bubble already
seen this session.

Added `sAudioLevelsCache` — a capped `LinkedHashMap<String, float[]>`
(120 entries, same access-order-LRU pattern as `sTextLayoutCache`/
`sPollOptionLayoutCache`), keyed on `seed + "_" + count`. `generateAudioLevels()`
now checks the cache before running the `Random` loop.

## 2. Poll option row RectF/Path allocations (real one — this ran every frame)
`PollRenderer.draw()` runs on every `onDraw()` — i.e. every scroll frame a
poll bubble is on screen, not just on measure/bind. The old code did:
```java
host.pollOptionRects.clear();
...
host.pollOptionRects.add(new RectF(rowRect));   // n allocations/frame
...
RectF fillRect = new RectF(...);                // + n more/frame
android.graphics.Path fp = new android.graphics.Path(); // + n more/frame
```
That's up to 3×(option count) allocations *per poll bubble per frame*
during a fling — real GC churn with a couple of polls on screen.

Fixed by:
- `pollOptionRects` is now grown/shrunk only when option count changes
  (already gated by `bindPoll()`'s `requestLayoutIfSizeChanged()`), and each
  row's `RectF` is mutated in place via `.set(...)` every draw instead of
  being reallocated.
- Added two reusable scratch fields on the host view —
  `pollFillRectScratch` (RectF) and `pollFillClipPath` (Path) — and the
  fill-bar drawing now calls `.set()`/`.reset()` on those instead of `new`.
- Touch hit-testing (`pollOptionRects.get(i).contains(ex, ey)`) is
  unaffected — it only ever reads, and the rects are guaranteed current as
  of the last `draw()`.

## 3. Location static-map thumbnail — added to the existing decoded-bitmap pool
`MessagePagingAdapter` already has `DECODED_BITMAP_CACHE`, an in-memory
`LruCache<String, Bitmap>` (sized to 1/8 heap) used for media/reel
thumbnails to skip Glide's decode step on scroll-back. The location
Google-Static-Maps thumbnail wasn't wired into it, so every rebind of a
location bubble re-hit Glide (disk-cache hit, but still a decode +
host-object lookup).

Now checks `DECODED_BITMAP_CACHE.get(thumbUrl)` first (URL is a stable key —
fixed zoom/size per lat/lng) and only falls through to `glide(...).into(...)`
on a miss; the `onResourceReady` callback populates the pool exactly like
the media-image path already does.

## Net effect
All three are pure additions — no existing invalidation/dirty-flag/
signature-check logic was touched, so behavior on a cache miss is
byte-for-byte identical to before. Colour-only changes (bubble/tick/
reaction/forward-button paints) remain outside all of this — they were
already zero-cost and untouched.
