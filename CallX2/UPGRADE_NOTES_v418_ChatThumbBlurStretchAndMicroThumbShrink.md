# v418 — Chat received-media thumbnail: blur+stretch fix + micro-thumb shrink

## Problem
In the main chat bubble (canvas-rendered, `MessageBubbleCanvasView`), the
pre-download image preview (`m.thumbnailUrl`, the sender's tiny
ImageCompressor micro-thumb) was loaded and upscaled to bubble size with
**no blur transform** — so received images showed hard, blocky pixels
instead of a soft Telegram/WhatsApp-style preview. The BlurHash instant
placeholder was already correct; this bug only affected the *second*
stage (after BlurHash, before the real download finishes).

Other surfaces in the app (`ivImage` media-grid bubbles) already applied
`TinyThumbBlurTransformation` correctly — this fix brings the main canvas
bubble path to parity with them.

## Changes

1. **`ImageCompressor.java`** — micro-thumb shrunk further:
   `THUMB_SIZE` 24px → **8px**, `THUMB_QUALITY` 25 → **20**,
   `THUMB_TARGET_BYTES` 500B → **180B** (~99.99% below the original
   200px baseline). Safe to shrink because `TinyThumbBlurTransformation`
   always internally downscales to a 32px working size before blurring —
   a sharper source than that buys no visible fidelity, only extra bytes.

2. **`MessagePagingAdapter.java`** — added
   `.transform(new TinyThumbBlurTransformation(3))` to the 4 canvas-bubble
   Glide loads of the image micro-thumb (plaintext + Media-E2E branches).
   **Video thumbnails were deliberately left untouched** — a video's
   `thumbnailUrl` is a real extracted frame (300–480px, see
   `VideoCompressor.makeThumbnail`), not a blocky micro-thumb, so blurring
   it would only throw away real quality (matches the existing
   `derivedThumb` comment at the ivImage path).

3. Doc-comment updates in `ImageCompressor.java` / `TinyThumbBlurTransformation.java`
   to reflect the new 8px size.

## Not changed (already correct, left as-is)
- `BlurHash` encode/decode + `BlurHashPlaceholder` — already gives an
  instant, zero-network placeholder before any download starts. No
  changes needed there.
- Video micro-preview pipeline — uses a real frame, not a micro-thumb;
  blurring it would be a quality regression, not an improvement.

## Result
Received image bubbles now show a smooth, stretched blur preview
(Telegram-style) immediately, backed by an even smaller network payload
for the intermediate thumbnail stage.
