# Thumbnail upgrade — Steps 4 & 5: adaptive blur radius + lazy video frame

Continues the earlier ThumbHash migration / progressive-JPEG work (steps
1–3, already done). This pass covers the remaining two asks.

## 4. Adaptive blur radius (bubble-size aware)

**Problem:** the ThumbHash placeholder is always a decoded 32×32 bitmap.
It's bilinear-upscaled straight into `mediaRect` via a `BitmapShader`
(`MediaRenderer.draw()`). At a small bubble (~120dp, near
`MEDIA_MIN_WIDTH_DP`/`MEDIA_MIN_HEIGHT_DP`) that upscale is mild and looks
fine as-is. At a big bubble (~300dp, near `MEDIA_MAX_HEIGHT_DP`) the same
32px source is stretched much further and the plain bilinear filter starts
looking soft-blocky rather than like a genuine blur.

**Fix:**
- `MessageBubbleCanvasView` — new `mediaBitmapIsPlaceholder` flag, and a
  `setMediaBitmap(Bitmap, boolean isLowResPlaceholder)` overload. The
  existing single-arg `setMediaBitmap(Bitmap)` is now a thin wrapper that
  always passes `false`, so every pre-existing call site (real Glide/pool
  decodes, progressive-JPEG partials) is completely unaffected — only the
  two `ThumbHashPlaceholder.get(...)` call sites in `MessagePagingAdapter`
  (image block + video block) now pass `true`.
- `MediaRenderer.draw()` — in the shader-build (cache-miss) branch only:
  if `host.mediaBitmapIsPlaceholder`, run the bitmap through
  `blurPlaceholderForBubble()` before building the `BitmapShader`.
  - `adaptiveBlurRadiusFor()` interpolates a radius (1–5) from how much
    `mediaRect` upscales the 32px source: below a small-bubble threshold
    the radius stays at 1 (i.e. no extra blur — plain upscale is already
    fine), scaling up to 5 at/above a big-bubble threshold.
  - `blurPlaceholderForBubble()` always works on a **copy** — never the
    original bitmap. `ThumbHashPlaceholder`'s `LruCache` hands the exact
    same decoded `Bitmap` instance to every bubble sharing that hash
    string, so blurring in place would corrupt every other bubble
    currently showing it.
  - This only runs on a genuine cache miss (bitmap or rect changed) —
    same trigger the shader rebuild itself already used — so it costs
    nothing on the 30–60fps redraw path, only once per bind/resize.
  - The shader-identity cache (`cachedShaderBitmap`) still compares
    against the *original* placeholder bitmap, not the blurred copy, so
    cache invalidation logic is untouched.

Scope: single-image and single-video bubbles only (the two call sites
that show a ThumbHash placeholder). Media-group grid cells weren't asked
for and use fixed-size cells anyway, so left untouched.

## 5. Lazy video first-frame load

**Problem:** `MessagePagingAdapter`'s video block fired the real poster
frame (`vThumbUrl` — a 300–480px `VideoCompressor.makeThumbnail` frame)
in the very same bind pass as the ThumbHash placeholder — same-frame, no
gap. During a fast scroll this meant every video bubble that flew past
still kicked off a decrypt+Glide fetch for a frame the user never
actually looked at.

**Fix:** new `VIDEO_FRAME_LAZY_HANDLER` (main-looper `Handler`) +
`VIDEO_FRAME_LAZY_DELAY_MS = 220`. Only the genuine fetch-miss path is
delayed:
- `DECODED_BITMAP_CACHE` pool-**hit** (already decoded, zero network/
  decrypt cost) still applies immediately — no reason to delay something
  that's free.
- The pool-**miss** path (`resolveThumbMediaKeyAsync(...)` → decrypt/
  Glide-load) is now wrapped in `VIDEO_FRAME_LAZY_HANDLER.postDelayed(...,
  220)`. The bind token (`h.canvasBindToken`) is re-checked once the delay
  elapses, exactly like every other async callback in this file — a
  rebind/recycle in the meantime just silently skips the now-stale fetch.

Net effect: the ThumbHash placeholder is what actually renders first for
every video bubble; the real poster frame only starts fetching ~220ms
later, and only for bubbles still bound to the same message at that
point (i.e. not flicked straight past during a fast scroll).

## Bonus bug fix: image ThumbHash was coming out blank for every ratio

**Root cause:** `ChatMediaController`'s image-upload path decoded
`result.thumbFile` at `inSampleSize=4` before calling `ThumbHash.encode()`.
That `4` was tuned back when `ImageCompressor.THUMB_SIZE` was ~200px — a
1/4-res decode of a 200px thumb is still plenty of data for a hash.

Step 2 of this whole upgrade (removing the WebP micro-thumb stage)
shrank `ImageCompressor.THUMB_SIZE` down to **8px**. Nobody re-tuned the
`inSampleSize=4` decode sitting downstream of it — decoding an
already-8px file at 1/4 res leaves roughly 2px of real image data,
nowhere near enough for `ThumbHash.encode()` to produce a meaningful
hash. Result: every image's placeholder came out blank/malformed,
regardless of aspect ratio — it wasn't a ratio-specific bug, the two
shrink steps had just quietly stacked into a "double downsample".

**Fix:** `inSampleSize` changed `4 → 1` for the image path only —
`thumbFile` is already tiny, so decode it at full res (`ThumbHash.encode()`
still caps/downscales to its own internal 100×100 max, so this costs
nothing extra). The **video** path (`vr.thumbFile`, a real 300–480px
extracted frame) was never affected by the THUMB_SIZE shrink and still
correctly uses `inSampleSize=4` — left untouched.

## Net result of steps 4 + 5

- Small image/video bubbles: same as before (no extra blur, ThumbHash →
  real frame/image as before).
- Big image/video bubbles: ThumbHash placeholder now reads as a properly
  soft blur instead of a slightly-blocky upscaled 32px image.
- Video bubbles during fast scroll: no more instant real-frame fetch on
  every bubble that merely passes through view — placeholder shows,
  real-frame fetch only starts ~220ms later if the bubble is still bound.
- No changes to steps 1–3 (ThumbHash migration, WebP micro-thumb removal,
  progressive JPEG) — this pass only touches the placeholder-blur draw
  path and the video-thumb fetch timing.
