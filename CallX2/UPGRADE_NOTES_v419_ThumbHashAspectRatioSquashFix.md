# v419 — ThumbHash placeholder: fixed square-squash for all non-1:1 images

## Problem
`ThumbHash.decode(hash, width, height)` correctly reconstructed the
image's real aspect ratio internally (from the ratio bits in the hash
header, via `thumbHashToRGBA()` -> `img.width/img.height`), but then threw
that away: every call site (`ThumbHashPlaceholder.get/getAsync`) always
passes a fixed square box (32, 32), and `decode()` blindly
`Bitmap.createScaledBitmap(raw, width, height, true)`'d the aspect-correct
`raw` bitmap into that exact square — non-uniformly squashing every
non-1:1 photo's placeholder into a square. Only genuinely-square source
images passed through undistorted.

## Fix
`core/src/main/java/com/callx/app/utils/ThumbHash.java` — `decode()` now
fits the aspect-correct `raw` bitmap inside the requested box by scaling
both dimensions by the same factor (`min(width/img.width,
height/img.height)`) instead of stretching to an exact width x height.
The returned bitmap's own pixel dimensions now preserve the real ratio;
`MediaRenderer`'s existing center-crop-into-mediaRect step (unchanged)
handles fitting it into the bubble regardless of its exact size, same as
it already does for real decoded photos.

## Not changed
- `ThumbHashPlaceholder` cache-key shape (`hash_width_height`) — left as
  is; still 32x32 in the key even though the cached bitmap's actual pixel
  dimensions now vary per image, which is fine (one cache entry per
  hash either way).
- Video BlurHash/ThumbHash path, encode() side, MediaRenderer's blur pass
  — untouched, not part of this bug.

## Result
Received-image blur placeholders now show in the photo's real shape
(portrait/landscape/panorama) instead of always square, matching the
bubble's own aspect-ratio sizing.
