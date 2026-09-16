# v420 — ThumbHash: full-res fallback when micro-thumb encode fails

## Problem
Extreme-aspect-ratio images (tall screenshots, panoramas) could still
occasionally leave `blurHash` null despite ImageCompressor's
`MIN_THUMB_SHORT_SIDE` floor (v419) — a corrupt/edge-case WebP
round-trip on the ~8px micro-thumb file. Receiver then had nothing to
decode: flat gray placeholder box instead of a colored blur, for that
one message only.

## Fix
`ChatMediaController.java` (`doStartImageUpload`, image send path) —
if the primary micro-thumb decode/encode leaves `blurHash == null`,
fall back to a fresh, bounds-limited decode straight from the
already-compressed full-res file (`result.fullFile`): a header-only
`inJustDecodeBounds` read picks an `inSampleSize` that lands around a
~64px longest side (real aspect ratio preserved, whatever it is), then
`ThumbHash.encode()` runs on that instead.

## Cost
- Normal images: **zero extra work** — fallback block is skipped
  entirely when the primary path already produced a hash.
- Only the rare extreme-ratio failure case pays one extra small decode
  — happens on the background upload thread, not the UI/scroll path,
  and costs no extra network/upload (local CPU only, milliseconds).

## Not changed
- Video ThumbHash path — out of scope, this send only covers images.
- `ThumbHash.java` decode/encode math, `MIN_THUMB_SHORT_SIDE` floor —
  untouched, this is an additional safety net on top of both.
