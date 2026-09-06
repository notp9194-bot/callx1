# v373 — Single-image bubbles: header-only aspect-ratio read shrinks the first-time "pop"

## The problem

A single-image bubble sizes itself from its real aspect ratio
(`MessageBubbleCanvasView.mediaAspectRatio`), with two existing
fast-paths already in place:

1. Width/height metadata carried on the `Message` itself, captured at
   send time (`ChatMediaController`) and synced to both sides —
   correct on the very first layout pass, zero placeholder flash.
2. `MEDIA_ASPECT_CACHE` — a previously-decoded image (scrolled past
   before, or already in Glide's memory cache) restores its ratio
   synchronously on rebind.

The gap: a message sent before that metadata field existed (or one
where dimension resolution failed at send time) AND an image this
process has genuinely never decoded before. `bindMedia()` has nothing
to size it with, so it shows the square/4:3 placeholder and only learns
the real ratio once the **full** Glide thumbnail decode finishes
(downsample + RGB565 convert) — which is when the visible square→real
"pop" happens, on top of the relayout that decode triggers.

## The fix

For the locally-available case (sent, or received-and-cached — i.e.
`loadSrc` is already a local `File`/`Uri`, no network wait), fire a
**header-only** dimension read (`BitmapFactory.Options
.inJustDecodeBounds = true`) in parallel with the full Glide decode,
on its own single-thread executor (`ASPECT_BOUNDS_EXECUTOR`, same
shape as the existing `LOCAL_AVAIL_EXECUTOR`). A bounds-only read only
parses the image header — no pixel decode, no bitmap allocation — so
it resolves in a fraction of the time the full thumbnail decode takes.

The moment it returns, `MessageBubbleCanvasView.applyKnownAspectRatioEarly()`
applies the ratio and relayouts the bubble immediately — while the
bitmap itself is still null, so the placeholder box just resizes to
the correct proportions. By the time the full decode finishes and
`setMediaBitmap()` runs, the ratio is already known, so that call's own
relayout check is skipped — net effect is one relayout that happens
right after a header read instead of after a full decode, not two
relayouts.

Guarded the same way the rest of this file guards async callbacks:
`canvasBindToken` so a holder recycled/rebound mid-read is never
touched, and a small result cache (`ASPECT_BOUNDS_CACHE`) so the same
image is never bounds-read twice.

## What this doesn't change

- Images with known metadata or a `MEDIA_ASPECT_CACHE` hit: unaffected,
  `applyKnownAspectRatioEarly()` no-ops immediately
  (`mediaAspectRatio > 0f` already).
- Media-group grid cells: unaffected — grid cells are fixed-size
  squares/rects per layout position, not aspect-ratio-driven, so
  there's no placeholder→real transition to shrink there.
- Remote (not-yet-downloaded) received images: unaffected — a
  bounds-only read still needs the bytes on disk, so this only applies
  once the file is local. Skipped intentionally for `loadSrc` that's
  still a plain remote URL string (would mean a second network fetch
  just for header bytes, not worth it).
